import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import {
  applyEach,
  disabled,
  form,
  FormField,
  FormRoot,
  hidden,
  maxLength,
  minLength,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import {
  SignatureCaptureMethod,
  SignatureSubmissionDTO,
  SigningFieldValueDTO,
  SigningResultDTO,
  SigningSessionDTO,
  VerificationMethod,
  VerificationResultDTO,
  VerificationStatus,
} from '@models/signing';
import { FieldType, SignatoryStatus, SigningFieldDTO } from '@models/workflow';
import { SigningPortalApi } from '@services/signing/signing-portal-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { saveFile } from '@shared/save-file';
import { SignaturePad } from '@shared/signature-pad/signature-pad';
import { StatusChip } from '@shared/status-chip/status-chip';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { BrandLogo } from '@shared/brand-logo/brand-logo';
import { ThemeToggle } from '@shared/theme-toggle/theme-toggle';
import { firstValueFrom } from 'rxjs';

interface FieldRow {
  fieldId: string;
  type: FieldType | null;
  label: string;
  required: boolean;
  placeholder: string;
  maxLength: number | null;
  regex: string | null;
  options: string[];
  value: string;
  checked: boolean;
}

interface SignatureModel {
  captureMethod: SignatureCaptureMethod;
  typedName: string;
  signatureImage: string;
  consentAccepted: boolean;
  intentConfirmed: boolean;
  fieldValues: FieldRow[];
}

interface CodeModel {
  code: string;
}

interface DeclineModel {
  reason: string;
}

/** What the visitor sees, derived from the session and what they did on the page. */
type PortalView =
  | 'loading'
  | 'unavailable'
  | 'result'
  | 'signed'
  | 'declined'
  | 'waiting'
  | 'closed'
  | 'account'
  | 'verify'
  | 'sign';

const CAPTURE_LABELS: Record<SignatureCaptureMethod, string> = {
  [SignatureCaptureMethod.DRAWN]: 'Draw',
  [SignatureCaptureMethod.TYPED]: 'Type',
  [SignatureCaptureMethod.UPLOADED_IMAGE]: 'Upload',
};

/** The API's answer when the browser has no (or an expired) verified signing session. */
const VERIFY_AGAIN = /verify your identity/i;
const RESEND_SECONDS = 60;
const MAX_SIGNATURE_BYTES = 1024 * 1024;

/** Fields the signatory completes themselves (signatures and initials come from the capture). */
const isInputField = (field: SigningFieldDTO) =>
  !!field.id &&
  !field.readOnly &&
  !field.completed &&
  field.fieldType !== FieldType.SIGNATURE &&
  field.fieldType !== FieldType.INITIALS;

const today = () => {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
};

/** A whole-value RegExp for a field's rule (the API matches the entire value), or undefined. */
const fullMatch = (regex: string | null): RegExp | undefined => {
  if (!regex) {
    return undefined;
  }
  try {
    return new RegExp(`^(?:${regex})$`);
  } catch {
    return undefined;
  }
};

/** Message of an API error, including blob responses (document, pages, completed copy). */
async function errorText(error: unknown, fallback: string): Promise<string> {
  if (error instanceof HttpErrorResponse && error.error instanceof Blob) {
    try {
      const problem = JSON.parse(await error.error.text());
      return problem?.detail || problem?.title || fallback;
    } catch {
      return error.status === 0 ? 'The StarSign service could not be reached.' : fallback;
    }
  }
  return apiErrorMessage(error, fallback);
}

async function sha256Hex(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/**
 * The external signing portal (/sign/{token}): an external signatory, not signed in, opens
 * the link from their invitation email, verifies their identity when the sender asked for
 * it, reviews the document, completes their fields and signs or declines.
 */
@Component({
  selector: 'app-sign-portal',
  imports: [
    BrandLogo,
    ThemeToggle,
    DatePipe,
    FieldErrors,
    FormField,
    FormRoot,
    NgTemplateOutlet,
    RouterLink,
    SignaturePad,
    StatusChip,
  ],
  templateUrl: './sign-portal.html',
})
export class SignPortal {
  readonly token = input.required<string>();

  private readonly api = inject(SigningPortalApi);

  // ------------------------------------------------------------------------ session
  protected readonly session = signal<SigningSessionDTO | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly result = signal<SigningResultDTO | null>(null);

  protected readonly view = computed<PortalView>(() => {
    const s = this.session();
    if (this.result()) {
      return 'result';
    }
    if (this.loadError()) {
      return 'unavailable';
    }
    if (!s) {
      return 'loading';
    }
    if (s.alreadySigned || s.signatoryStatus === SignatoryStatus.SIGNED) {
      return 'signed';
    }
    switch (s.signatoryStatus) {
      case SignatoryStatus.DECLINED:
        return 'declined';
      case SignatoryStatus.PENDING:
        return 'waiting';
      case SignatoryStatus.EXPIRED:
      case SignatoryStatus.CANCELLED:
      case SignatoryStatus.SKIPPED:
        return 'closed';
    }
    if (s.verificationMethod === VerificationMethod.INTERNAL_ACCOUNT) {
      return 'account';
    }
    return s.verificationRequired && !s.verified ? 'verify' : 'sign';
  });

  protected readonly closedMessage = computed(() => {
    switch (this.session()?.signatoryStatus) {
      case SignatoryStatus.EXPIRED:
        return 'The time for signing this document has run out. Ask the sender for a new request.';
      case SignatoryStatus.CANCELLED:
        return 'The sender has cancelled this signing request.';
      default:
        return 'Your signature is no longer needed on this document.';
    }
  });

  /** Organisation-level wording for the portal, when the sender's organisation set some. */
  protected readonly branding = computed(() => this.session()?.branding ?? null);

  // ------------------------------------------------------------------- verification
  protected readonly verification = signal<VerificationResultDTO | null>(null);
  protected readonly verifyError = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly resendIn = signal(0);
  private resendTimer: ReturnType<typeof setInterval> | null = null;

  protected readonly codeSent = computed(() => {
    const status = this.verification()?.status;
    return status === VerificationStatus.PENDING || status === VerificationStatus.FAILED;
  });
  protected readonly isSms = computed(
    () => this.session()?.verificationMethod === VerificationMethod.SMS_OTP,
  );
  protected readonly canSendCode = computed(() => {
    const method = this.session()?.verificationMethod;
    return method === VerificationMethod.EMAIL_OTP || method === VerificationMethod.SMS_OTP;
  });

  private readonly codeModel = signal<CodeModel>({ code: '' });
  protected readonly codeForm = form(
    this.codeModel,
    (path) => {
      required(path.code, { message: 'Enter the code we sent you.' });
      pattern(path.code, /^\s*\d{4,10}\s*$/, { message: 'The code is made of digits only.' });
    },
    {
      submission: {
        action: async () => {
          this.verifyError.set(null);
          try {
            const outcome = await firstValueFrom(
              this.api.confirmVerification(this.token(), {
                code: this.codeModel().code.replace(/\s/g, ''),
              }),
            );
            this.verification.set(outcome);
            this.onVerificationOutcome(outcome);
          } catch (error) {
            this.verifyError.set(apiErrorMessage(error, 'The code could not be checked.'));
          }
          return undefined;
        },
      },
    },
  );

  // ----------------------------------------------------------------------- document
  protected readonly pageCount = computed(() => {
    const s = this.session();
    return s?.pageCount ?? s?.pages?.length ?? 0;
  });
  protected readonly currentPage = signal(1);
  protected readonly pageUrls = signal<Record<number, string>>({});
  protected readonly pageError = signal<string | null>(null);
  protected readonly pageLoading = signal(false);
  protected readonly pdfUrl = signal<string | null>(null);
  protected readonly documentSha256 = signal<string | null>(null);
  protected readonly documentError = signal<string | null>(null);
  private documentRequested = false;
  private readonly objectUrls: string[] = [];

  /** This signatory's fields on the page being viewed, as boxes over the preview. */
  protected readonly pageFields = computed(() =>
    (this.session()?.fields ?? []).filter(
      (field) => (field.pageNumber ?? 1) === this.currentPage() && field.widthRatio && field.heightRatio,
    ),
  );

  // ---------------------------------------------------------------- signature form
  protected readonly captureMethods = computed<SignatureCaptureMethod[]>(() => {
    const allowed = this.session()?.allowedCaptureMethods;
    return allowed?.length
      ? allowed
      : [SignatureCaptureMethod.DRAWN, SignatureCaptureMethod.TYPED];
  });
  protected readonly captureLabel = CAPTURE_LABELS;
  protected readonly Capture = SignatureCaptureMethod;
  protected readonly FieldType = FieldType;
  protected readonly uploadError = signal<string | null>(null);

  protected readonly signature = signal<SignatureModel>({
    captureMethod: SignatureCaptureMethod.DRAWN,
    typedName: '',
    signatureImage: '',
    consentAccepted: false,
    intentConfirmed: false,
    fieldValues: [],
  });

  protected readonly signatureForm = form(
    this.signature,
    (path) => {
      hidden(
        path.typedName,
        ({ valueOf }) => valueOf(path.captureMethod) !== SignatureCaptureMethod.TYPED,
      );
      required(path.typedName, {
        when: ({ valueOf }) => valueOf(path.captureMethod) === SignatureCaptureMethod.TYPED,
        message: 'Type your full legal name.',
      });
      minLength(path.typedName, 3, { message: 'Type your full legal name.' });
      maxLength(path.typedName, 200);
      validate(path.typedName, ({ value }) => {
        const expected = this.session()?.signatoryName?.trim().toLowerCase();
        const typed = value().trim().toLowerCase();
        return expected && typed && typed !== expected
          ? {
              kind: 'nameMismatch',
              message: `The typed name must match "${this.session()?.signatoryName}".`,
            }
          : null;
      });

      hidden(
        path.signatureImage,
        ({ valueOf }) => valueOf(path.captureMethod) === SignatureCaptureMethod.TYPED,
      );
      required(path.signatureImage, {
        when: ({ valueOf }) => valueOf(path.captureMethod) !== SignatureCaptureMethod.TYPED,
        message: 'Draw or upload your signature.',
      });

      validate(path.consentAccepted, ({ value }) =>
        value()
          ? null
          : { kind: 'consent', message: 'You must accept the electronic signature terms.' },
      );
      validate(path.intentConfirmed, ({ value }) =>
        value()
          ? null
          : { kind: 'intent', message: 'Confirm that you intend to sign this document.' },
      );
      disabled(path.intentConfirmed, ({ valueOf }) =>
        valueOf(path.consentAccepted) ? false : 'Accept the terms first',
      );

      applyEach(path.fieldValues, (row) => {
        required(row.value, {
          when: ({ valueOf }) => valueOf(row.required) && valueOf(row.type) !== FieldType.CHECKBOX,
          message: 'This field is required.',
        });
        validate(row.checked, ({ value, valueOf }) =>
          valueOf(row.required) && valueOf(row.type) === FieldType.CHECKBOX && !value()
            ? { kind: 'required', message: 'Tick this box to continue.' }
            : null,
        );
        maxLength(row.value, ({ valueOf }) => valueOf(row.maxLength) ?? undefined);
        pattern(row.value, ({ valueOf }) => {
          const type = valueOf(row.type);
          return type === FieldType.DATE || type === FieldType.CHECKBOX
            ? undefined
            : fullMatch(valueOf(row.regex));
        });
      });
    },
    {
      submission: {
        action: async () => {
          this.submitError.set(null);
          const viewed = this.documentSha256();
          if (!viewed) {
            this.submitError.set(
              this.documentError() ?? 'Wait for the document to finish loading, then sign.',
            );
            return undefined;
          }
          try {
            const outcome = await firstValueFrom(
              this.api.sign(this.token(), this.toSubmission(viewed)),
            );
            this.result.set(outcome);
            toast.success('Signature applied', {
              description: 'Your signature has been recorded in the audit chain.',
            });
          } catch (error) {
            this.handleActionError(error, 'Your signature could not be applied.');
          }
          return undefined;
        },
      },
    },
  );

  protected readonly savingDraft = signal(false);

  // ------------------------------------------------------------------ decline form
  protected readonly declineOpen = signal(false);
  private readonly decline = signal<DeclineModel>({ reason: '' });
  protected readonly declineForm = form(
    this.decline,
    (path) => {
      required(path.reason, {
        when: () => !!this.session()?.requireDeclineReason,
        message: 'Tell the sender why you are declining.',
      });
      minLength(path.reason, 10, { message: 'Give at least 10 characters.' });
      maxLength(path.reason, 1000);
    },
    {
      submission: {
        action: async () => {
          this.submitError.set(null);
          try {
            const outcome = await firstValueFrom(
              this.api.decline(this.token(), {
                idempotencyKey: this.declineKey,
                reason: this.decline().reason.trim() || null,
              }),
            );
            this.declineOpen.set(false);
            this.result.set(outcome);
            toast.info('You declined to sign', { description: 'The sender has been notified.' });
          } catch (error) {
            this.declineOpen.set(false);
            this.handleActionError(error, 'Your decision could not be recorded.');
          }
          return undefined;
        },
      },
    },
  );

  // ------------------------------------------------------------------------ state
  protected readonly submitError = signal<string | null>(null);
  protected readonly downloading = signal(false);
  protected readonly downloadError = signal<string | null>(null);
  /** One key per page visit, so a retried submission is recognised by the API. */
  private readonly signKey = crypto.randomUUID();
  private readonly declineKey = crypto.randomUUID();

  protected readonly busy = computed(
    () =>
      this.signatureForm().submitting() || this.declineForm().submitting() || this.savingDraft(),
  );
  protected readonly completedFields = computed(
    () =>
      this.signature().fieldValues.filter((row) =>
        row.type === FieldType.CHECKBOX ? row.checked : row.value.trim(),
      ).length,
  );
  protected readonly steps = computed(() => {
    const f = this.signatureForm;
    const model = this.signature();
    const fieldsDone = model.fieldValues.every(
      (_, i) => f.fieldValues[i].value().valid() && f.fieldValues[i].checked().valid(),
    );
    const captured =
      model.captureMethod === SignatureCaptureMethod.TYPED
        ? f.typedName().valid()
        : f.signatureImage().valid();
    return [
      { label: 'Review document', done: !!this.documentSha256() },
      { label: 'Complete fields', done: fieldsDone },
      { label: 'Capture signature', done: captured },
      {
        label: 'Consent & intent',
        done: f.consentAccepted().valid() && f.intentConfirmed().valid(),
      },
    ];
  });

  constructor() {
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => {
      this.objectUrls.forEach((url) => URL.revokeObjectURL(url));
      this.stopResendTimer();
    });

    // Open the session whenever the token changes.
    effect(() => {
      const token = this.token();
      untracked(() => this.loadSession(token));
    });

    // The document is fetched once the visitor may see it (verified when required).
    effect(() => {
      if (this.view() === 'sign' && !this.documentRequested) {
        this.documentRequested = true;
        untracked(() => {
          this.loadDocument();
          this.showPage(1);
        });
      }
    });
  }

  // ---------------------------------------------------------------------- loading
  private async loadSession(token: string): Promise<void> {
    this.session.set(null);
    this.loadError.set(null);
    try {
      const session = await firstValueFrom(this.api.getSession(token));
      this.session.set(session);
      this.seedForm(session);
    } catch (error) {
      this.loadError.set(
        apiErrorMessage(error, 'This signing link could not be opened. Ask the sender for a new one.'),
      );
    }
  }

  private seedForm(session: SigningSessionDTO): void {
    const methods = this.captureMethods();
    this.signature.update((model) => ({
      ...model,
      captureMethod: methods.includes(model.captureMethod) ? model.captureMethod : methods[0],
      typedName: model.typedName || session.signatoryName || '',
      fieldValues: (session.fields ?? [])
        .filter(isInputField)
        .toSorted((a, b) => (a.tabOrder ?? 0) - (b.tabOrder ?? 0))
        .map((field) => {
          const fallback =
            field.fieldType === FieldType.DATE
              ? today()
              : field.fieldType === FieldType.FULL_NAME
                ? (session.signatoryName ?? '')
                : '';
          const initial = field.defaultValue ?? '';
          return {
            fieldId: field.id ?? '',
            type: field.fieldType ?? null,
            label: field.label || this.typeLabel(field.fieldType),
            required: !!field.required,
            placeholder: field.placeholder ?? '',
            maxLength: field.maxLength ?? null,
            regex: field.validationRegex ?? null,
            options: field.options ?? [],
            value: field.fieldType === FieldType.CHECKBOX ? '' : initial || fallback,
            checked: field.fieldType === FieldType.CHECKBOX && initial.toLowerCase() === 'true',
          };
        }),
    }));
  }

  private async loadDocument(): Promise<void> {
    this.documentError.set(null);
    try {
      const pdf = await firstValueFrom(this.api.getDocument(this.token()));
      this.pdfUrl.set(this.track(URL.createObjectURL(pdf)));
      if (!globalThis.crypto?.subtle) {
        this.documentError.set(
          'This browser cannot check the document fingerprint. Open the link over HTTPS or in another browser.',
        );
        return;
      }
      this.documentSha256.set(await sha256Hex(pdf));
    } catch (error) {
      const message = await errorText(error, 'The document could not be loaded.');
      if (VERIFY_AGAIN.test(message)) {
        this.requireVerificationAgain();
        return;
      }
      this.documentError.set(message);
    }
  }

  protected async showPage(page: number): Promise<void> {
    const count = this.pageCount();
    if (page < 1 || (count && page > count)) {
      return;
    }
    this.currentPage.set(page);
    this.pageError.set(null);
    if (this.pageUrls()[page]) {
      return;
    }
    this.pageLoading.set(true);
    try {
      const image = await firstValueFrom(this.api.getPagePreview(this.token(), page));
      const url = this.track(URL.createObjectURL(image));
      this.pageUrls.update((urls) => ({ ...urls, [page]: url }));
    } catch (error) {
      const message = await errorText(error, `Page ${page} could not be shown.`);
      if (VERIFY_AGAIN.test(message)) {
        this.requireVerificationAgain();
      } else {
        this.pageError.set(message);
      }
    } finally {
      this.pageLoading.set(false);
    }
  }

  // ----------------------------------------------------------------- verification
  protected async sendCode(): Promise<void> {
    const s = this.session();
    if (!s || this.sending() || this.resendIn() > 0) {
      return;
    }
    this.sending.set(true);
    this.verifyError.set(null);
    try {
      const outcome = await firstValueFrom(
        this.api.startVerification(this.token(), { method: s.verificationMethod ?? null }),
      );
      this.verification.set(outcome);
      this.codeModel.set({ code: '' });
      this.codeForm().reset();
      if (outcome.verified) {
        this.markVerified();
      } else {
        this.startResendTimer();
        toast.success('Code sent', {
          description: outcome.destinationMasked ? `Sent to ${outcome.destinationMasked}` : undefined,
        });
      }
    } catch (error) {
      this.verifyError.set(apiErrorMessage(error, 'The code could not be sent.'));
    } finally {
      this.sending.set(false);
    }
  }

  private onVerificationOutcome(outcome: VerificationResultDTO): void {
    switch (outcome.status) {
      case VerificationStatus.VERIFIED:
        this.markVerified();
        toast.success('Identity verified');
        break;
      case VerificationStatus.FAILED:
        this.verifyError.set(
          `That code is not right. ${outcome.attemptsRemaining ?? 0} attempt${outcome.attemptsRemaining === 1 ? '' : 's'} left.`,
        );
        break;
      case VerificationStatus.LOCKED:
        this.verifyError.set('Too many wrong codes. Ask for a new code.');
        this.resendIn.set(0);
        break;
      case VerificationStatus.EXPIRED:
        this.verifyError.set('That code has expired. Ask for a new code.');
        this.resendIn.set(0);
        break;
      default:
        this.verifyError.set('The code could not be checked. Try again.');
    }
  }

  private markVerified(): void {
    this.verifyError.set(null);
    this.stopResendTimer();
    this.session.update((s) => (s ? { ...s, verified: true } : s));
  }

  /** The signing session cookie is missing or expired: send the visitor back to verification. */
  private requireVerificationAgain(): void {
    this.documentRequested = false;
    this.verification.set(null);
    this.verifyError.set(
      'Your verified session has ended, or this browser did not keep it (cookies may be blocked). Verify again to continue.',
    );
    this.session.update((s) => (s ? { ...s, verified: false } : s));
  }

  private startResendTimer(): void {
    this.stopResendTimer();
    this.resendIn.set(RESEND_SECONDS);
    this.resendTimer = setInterval(() => {
      this.resendIn.update((seconds) => Math.max(0, seconds - 1));
      if (this.resendIn() === 0) {
        this.stopResendTimer();
      }
    }, 1000);
  }

  private stopResendTimer(): void {
    if (this.resendTimer) {
      clearInterval(this.resendTimer);
      this.resendTimer = null;
    }
  }

  // ---------------------------------------------------------------------- signing
  protected onUpload(event: Event): void {
    const inputEl = event.target as HTMLInputElement;
    const file = inputEl.files?.[0];
    this.uploadError.set(null);
    if (!file) {
      return;
    }
    if (file.type !== 'image/png') {
      this.uploadError.set('Choose a PNG image of your signature.');
      inputEl.value = '';
      return;
    }
    if (file.size > MAX_SIGNATURE_BYTES) {
      this.uploadError.set('The image can be at most 1 MB.');
      inputEl.value = '';
      return;
    }
    const reader = new FileReader();
    reader.onload = () =>
      this.signature.update((model) => ({ ...model, signatureImage: String(reader.result ?? '') }));
    reader.onerror = () => this.uploadError.set('The image could not be read.');
    reader.readAsDataURL(file);
  }

  protected clearUpload(fileInput: HTMLInputElement): void {
    fileInput.value = '';
    this.signature.update((model) => ({ ...model, signatureImage: '' }));
  }

  /** Switching method starts the capture afresh (a drawing is not an upload). */
  protected onMethodChange(): void {
    this.uploadError.set(null);
    this.signature.update((model) => ({ ...model, signatureImage: '' }));
  }

  protected async saveDraft(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.savingDraft.set(true);
    this.submitError.set(null);
    try {
      await firstValueFrom(this.api.saveFieldValues(this.token(), this.fieldValueDTOs()));
      toast.success('Progress saved', {
        description: 'You can come back to this link to finish signing.',
      });
    } catch (error) {
      this.handleActionError(error, 'Your entries could not be saved.');
    } finally {
      this.savingDraft.set(false);
    }
  }

  protected async downloadCompleted(): Promise<void> {
    if (this.downloading()) {
      return;
    }
    this.downloading.set(true);
    this.downloadError.set(null);
    try {
      const pdf = await firstValueFrom(this.api.downloadCompletedCopy(this.token()));
      const title = this.session()?.documentTitle || this.session()?.workflowTitle || 'document';
      saveFile(pdf, `${title.replace(/[\\/:*?"<>|]+/g, '-')}-signed.pdf`);
    } catch (error) {
      this.downloadError.set(await errorText(error, 'The completed copy could not be downloaded.'));
    } finally {
      this.downloading.set(false);
    }
  }

  protected openDecline(): void {
    this.submitError.set(null);
    this.declineOpen.set(true);
  }

  private handleActionError(error: unknown, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    if (VERIFY_AGAIN.test(message)) {
      this.requireVerificationAgain();
      return;
    }
    this.submitError.set(message);
  }

  /** Only fields with a value are sent: empty optional fields take the API's defaults. */
  private fieldValueDTOs(): SigningFieldValueDTO[] {
    return this.signature()
      .fieldValues.filter((row) => row.type === FieldType.CHECKBOX || row.value.trim())
      .map((row) =>
        row.type === FieldType.CHECKBOX
          ? { fieldId: row.fieldId, valueBoolean: row.checked }
          : row.type === FieldType.DATE
            ? { fieldId: row.fieldId, valueDate: row.value, valueText: row.value }
            : { fieldId: row.fieldId, valueText: row.value.trim() },
      );
  }

  private toSubmission(documentSha256Viewed: string): SignatureSubmissionDTO {
    const model = this.signature();
    const typed = model.captureMethod === SignatureCaptureMethod.TYPED;
    return {
      idempotencyKey: this.signKey,
      captureMethod: model.captureMethod,
      typedName: typed ? model.typedName.trim() : null,
      signatureFont: typed ? 'Plus Jakarta Sans' : null,
      signatureImage: typed ? null : model.signatureImage,
      consentAccepted: model.consentAccepted,
      intentConfirmed: model.intentConfirmed,
      fieldValues: this.fieldValueDTOs(),
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      documentSha256Viewed,
    };
  }

  protected typeLabel(type: FieldType | null | undefined): string {
    switch (type) {
      case FieldType.FULL_NAME:
        return 'Full name';
      case FieldType.JOB_TITLE:
        return 'Job title';
      case FieldType.ORGANISATION:
        return 'Organisation';
      case FieldType.DATE:
        return 'Date';
      case FieldType.CHECKBOX:
        return 'Checkbox';
      case FieldType.COMMENT:
        return 'Comment';
      case FieldType.SIGNATURE:
        return 'Signature';
      case FieldType.INITIALS:
        return 'Initials';
      default:
        return 'Field';
    }
  }

  private track(url: string): string {
    this.objectUrls.push(url);
    return url;
  }
}
