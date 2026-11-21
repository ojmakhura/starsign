import { DatePipe } from '@angular/common';
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
import { Router, RouterLink } from '@angular/router';
import {
  SignatureCaptureMethod,
  SignatureSubmissionDTO,
  SigningFieldValueDTO,
  SigningResultDTO,
  SigningSessionDTO,
} from '@models/signing';
import { FieldType, SignatoryStatus, SigningFieldDTO } from '@models/workflow';
import { InternalSigningApi } from '@services/signing/internal-signing-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { SignaturePad } from '@shared/signature-pad/signature-pad';
import { StatusChip } from '@shared/status-chip/status-chip';
import { apiErrorMessage } from '@store/app-state';
import { InternalSigningApiStore } from '@store/signing/internal-signing-api.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

interface FieldRow {
  fieldId: string;
  type: FieldType | null;
  label: string;
  pageNumber: number;
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

interface DeclineModel {
  reason: string;
}

/** What the signer sees, derived from the session and what they did on the page. */
type SignView =
  'loading' | 'unavailable' | 'result' | 'signed' | 'declined' | 'waiting' | 'closed' | 'sign';

const CAPTURE_LABELS: Record<SignatureCaptureMethod, string> = {
  [SignatureCaptureMethod.DRAWN]: 'Draw',
  [SignatureCaptureMethod.TYPED]: 'Type',
  [SignatureCaptureMethod.UPLOADED_IMAGE]: 'Upload',
};

const MAX_SIGNATURE_BYTES = 1024 * 1024;

/** Signature and initials fields are filled with the captured signature, not typed in. */
const isSignatureField = (type: FieldType | null | undefined) =>
  type === FieldType.SIGNATURE || type === FieldType.INITIALS;

/** Fields the signatory completes themselves. */
const isInputField = (field: SigningFieldDTO) =>
  !!field.id && !field.readOnly && !field.completed && !isSignatureField(field.fieldType);

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

/** Message of an API error, including blob responses (document and pages). */
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
 * Internal signing (/signing/workflows/{workflowId}/signatories/{signatoryId}): a signed-in
 * organisation user reviews the real document pages with their fields placed on them,
 * completes the fields and signs or declines. Their account is their verification.
 */
@Component({
  selector: 'app-sign-document',
  imports: [DatePipe, FieldErrors, FormField, FormRoot, RouterLink, SignaturePad, StatusChip],
  templateUrl: './sign-document.html',
})
export class SignDocument {
  readonly workflowId = input.required<string>();
  readonly signatoryId = input.required<string>();

  private readonly api = inject(InternalSigningApi);
  private readonly inbox = inject(InternalSigningApiStore);
  private readonly router = inject(Router);

  // ------------------------------------------------------------------------ session
  protected readonly session = signal<SigningSessionDTO | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly result = signal<SigningResultDTO | null>(null);

  protected readonly view = computed<SignView>(() => {
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
    return 'sign';
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

  /** This signatory's fields on the page being viewed, as boxes over the page image. */
  protected readonly pageFields = computed(() =>
    (this.session()?.fields ?? []).filter(
      (field) =>
        (field.pageNumber ?? 1) === this.currentPage() && field.widthRatio && field.heightRatio,
    ),
  );
  /** Pages that carry at least one of this signatory's fields. */
  protected readonly fieldPages = computed(
    () => new Set((this.session()?.fields ?? []).map((field) => field.pageNumber ?? 1)),
  );
  /** The field the signer is working on, highlighted on the page. */
  protected readonly activeFieldId = signal<string | null>(null);

  // ---------------------------------------------------------------- signature form
  protected readonly captureMethods = computed<SignatureCaptureMethod[]>(() => {
    const allowed = this.session()?.allowedCaptureMethods;
    return allowed?.length ? allowed : [SignatureCaptureMethod.DRAWN, SignatureCaptureMethod.TYPED];
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

  /** The entered value of each input field, by field id, for the overlay. */
  protected readonly rowsById = computed(
    () => new Map(this.signature().fieldValues.map((row) => [row.fieldId, row])),
  );

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
              this.api.signAsInternalUser(
                this.workflowId(),
                this.signatoryId(),
                this.toSubmission(viewed),
              ),
            );
            this.result.set(outcome);
            toast.success('Signature applied', {
              description: 'Your signature has been recorded in the audit chain.',
            });
            this.inbox.getPendingForCurrentUser();
          } catch (error) {
            this.submitError.set(apiErrorMessage(error, 'Your signature could not be applied.'));
          }
          return undefined;
        },
      },
    },
  );

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
              this.api.declineAsInternalUser(this.workflowId(), this.signatoryId(), {
                idempotencyKey: this.declineKey,
                reason: this.decline().reason.trim() || null,
              }),
            );
            this.declineOpen.set(false);
            this.result.set(outcome);
            toast.info('You declined to sign', { description: 'The sender has been notified.' });
            this.inbox.getPendingForCurrentUser();
          } catch (error) {
            this.declineOpen.set(false);
            this.submitError.set(apiErrorMessage(error, 'Your decision could not be recorded.'));
          }
          return undefined;
        },
      },
    },
  );

  // ------------------------------------------------------------------------ state
  protected readonly submitError = signal<string | null>(null);
  /** One key per page visit, so a retried submission is recognised by the API. */
  private readonly signKey = crypto.randomUUID();
  private readonly declineKey = crypto.randomUUID();

  protected readonly busy = computed(
    () => this.signatureForm().submitting() || this.declineForm().submitting(),
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
    inject(DestroyRef).onDestroy(() => this.releaseUrls());

    // Open the session whenever the route changes.
    effect(() => {
      const workflowId = this.workflowId();
      const signatoryId = this.signatoryId();
      untracked(() => this.loadSession(workflowId, signatoryId));
    });

    // The document is fetched once the signer can act on it.
    effect(() => {
      if (this.view() === 'sign' && !this.documentRequested) {
        this.documentRequested = true;
        untracked(() => {
          this.loadDocument();
          this.showPage(this.firstFieldPage());
        });
      }
    });
  }

  // ---------------------------------------------------------------------- loading
  private async loadSession(workflowId: string, signatoryId: string): Promise<void> {
    this.releaseUrls();
    this.session.set(null);
    this.loadError.set(null);
    this.result.set(null);
    this.documentRequested = false;
    this.pageUrls.set({});
    this.pdfUrl.set(null);
    this.documentSha256.set(null);
    this.documentError.set(null);
    try {
      const session = await firstValueFrom(this.api.getInternalSession(workflowId, signatoryId));
      this.session.set(session);
      this.seedForm(session);
    } catch (error) {
      this.loadError.set(
        apiErrorMessage(
          error,
          'This signing request could not be opened. It may have been cancelled or reassigned.',
        ),
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
        .toSorted(
          (a, b) =>
            (a.tabOrder ?? 0) - (b.tabOrder ?? 0) ||
            (a.pageNumber ?? 1) - (b.pageNumber ?? 1) ||
            (a.yRatio ?? 0) - (b.yRatio ?? 0),
        )
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
            pageNumber: field.pageNumber ?? 1,
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

  /** Opens on the first page the signer has to work on. */
  private firstFieldPage(): number {
    const pages = [...this.fieldPages()];
    return pages.length ? Math.min(...pages) : 1;
  }

  private async loadDocument(): Promise<void> {
    this.documentError.set(null);
    try {
      const pdf = await firstValueFrom(
        this.api.getInternalDocument(this.workflowId(), this.signatoryId()),
      );
      this.pdfUrl.set(this.track(URL.createObjectURL(pdf)));
      if (!globalThis.crypto?.subtle) {
        this.documentError.set(
          'This browser cannot check the document fingerprint. Open StarSign over HTTPS or in another browser.',
        );
        return;
      }
      this.documentSha256.set(await sha256Hex(pdf));
    } catch (error) {
      this.documentError.set(await errorText(error, 'The document could not be loaded.'));
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
      const image = await firstValueFrom(
        this.api.getInternalPagePreview(this.workflowId(), this.signatoryId(), page),
      );
      const url = this.track(URL.createObjectURL(image));
      this.pageUrls.update((urls) => ({ ...urls, [page]: url }));
    } catch (error) {
      this.pageError.set(await errorText(error, `Page ${page} could not be shown.`));
    } finally {
      this.pageLoading.set(false);
    }
  }

  // ------------------------------------------------------------- field navigation
  /** Clicking a box on the page takes the signer to its input in the tray. */
  protected focusField(field: SigningFieldDTO): void {
    const id = field.id ?? null;
    this.activeFieldId.set(id);
    const target = isSignatureField(field.fieldType)
      ? document.getElementById('signature-capture')
      : document.getElementById(`field-${id}`);
    target?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (
      target instanceof HTMLInputElement ||
      target instanceof HTMLTextAreaElement ||
      target instanceof HTMLSelectElement
    ) {
      target.focus({ preventScroll: true });
    }
  }

  /** Working on a field in the tray shows its page and highlights its box. */
  protected onRowFocus(row: FieldRow): void {
    this.activeFieldId.set(row.fieldId);
    if (row.pageNumber !== this.currentPage()) {
      this.showPage(row.pageNumber);
    }
  }

  /** What the box of a field shows: the entered value, or the signature for signature fields. */
  protected fieldPreview(field: SigningFieldDTO): string {
    const row = field.id ? this.rowsById().get(field.id) : undefined;
    if (!row) {
      return '';
    }
    if (row.type === FieldType.CHECKBOX) {
      return row.checked ? '✓' : '';
    }
    return row.value.trim();
  }

  protected signaturePreview(field: SigningFieldDTO): { image?: string; text?: string } | null {
    if (!isSignatureField(field.fieldType)) {
      return null;
    }
    const model = this.signature();
    if (model.captureMethod === SignatureCaptureMethod.TYPED) {
      const name = model.typedName.trim();
      if (!name) {
        return null;
      }
      return {
        text:
          field.fieldType === FieldType.INITIALS
            ? name
                .split(/\s+/)
                .map((part) => part[0]?.toUpperCase() ?? '')
                .join('')
            : name,
      };
    }
    return model.signatureImage ? { image: model.signatureImage } : null;
  }

  protected isSignatureField(field: SigningFieldDTO): boolean {
    return isSignatureField(field.fieldType);
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

  protected openDecline(): void {
    this.submitError.set(null);
    this.declineOpen.set(true);
  }

  protected backToInbox(): void {
    this.router.navigate(['/signing']);
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
      case FieldType.RADIO:
        return 'Choice';
      case FieldType.COMMENT:
        return 'Comment';
      case FieldType.SIGNATURE:
        return 'Signature';
      case FieldType.INITIALS:
        return 'Initials';
      default:
        return 'Text';
    }
  }

  private track(url: string): string {
    this.objectUrls.push(url);
    return url;
  }

  private releaseUrls(): void {
    this.objectUrls.splice(0).forEach((url) => URL.revokeObjectURL(url));
  }
}
