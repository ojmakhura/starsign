import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { hasPermission } from '@auth/page-permissions';
import { form, FormField, FormRoot } from '@angular/forms/signals';
import { Router, RouterLink } from '@angular/router';
import { DocumentStatus } from '@models/document';
import { VerificationMethod } from '@models/signing';
import { ExecutionMode, SignatoryType } from '@models/workflow';
import { DocumentApi } from '@services/document/document-api';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { WorkflowApi } from '@services/workflow/workflow-api';
import { FileDrop } from '@shared/file-drop/file-drop';
import { SessionStore } from '@store/session.store';
import { DocumentPageDTO } from '@models/document';
import { FieldPlacer } from './field-placer/field-placer';
import {
  fieldKind,
  PlacedField,
  PlacerRecipient,
  RECIPIENT_COLOURS,
  toSigningFields,
} from './field-placer/placed-field';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { DocumentApiStore } from '@store/document/document-api.store';
import { injectLocalStore } from '@store/local-store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import {
  MAX_SIGNATORIES,
  newDraft,
  newSignatory,
  toCreateWorkflow,
  toSignatory,
  WorkflowDraft,
  workflowDraftSchema,
} from './workflow-draft';

const ELIGIBLE = new Set<DocumentStatus>([
  DocumentStatus.UPLOADED,
  DocumentStatus.DRAFT,
  DocumentStatus.IN_PREPARATION,
  DocumentStatus.READY_FOR_SIGNING,
]);

type Step = 'document' | 'recipients' | 'fields' | 'review';

export const STEPS: { id: Step; label: string; icon: string }[] = [
  { id: 'document', label: 'Document', icon: 'bi-file-earmark-pdf' },
  { id: 'recipients', label: 'Recipients', icon: 'bi-people' },
  { id: 'fields', label: 'Place fields', icon: 'bi-pen' },
  { id: 'review', label: 'Review & send', icon: 'bi-send' },
];

/**
 * New signing request, in the steps of a desktop e-signature tool: pick or upload the PDF,
 * list the recipients in signing order, drop each recipient's fields onto the pages, then
 * review and send. Recipients who already have an account in the organisation also get the
 * request in their Signing inbox (the API links them by email address).
 */
@Component({
  selector: 'app-workflow-create',
  imports: [FieldErrors, FieldPlacer, FileDrop, FormField, FormRoot, RouterLink],
  templateUrl: './workflow-create.html',
})
export class WorkflowCreate {
  /** ?documentId=… preselects the document (bound by withComponentInputBinding). */
  readonly documentId = input<string>();

  private readonly workflowApi = inject(WorkflowApi);
  private readonly documentApi = inject(DocumentApi);
  private readonly organisationApi = inject(OrganisationApi);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly steps = STEPS;
  protected readonly step = signal<Step>('document');
  protected readonly stepIndex = computed(() => STEPS.findIndex((s) => s.id === this.step()));
  /** Steps the sender has reached; later ones can't be jumped to yet. */
  protected readonly reached = signal(0);

  /** Fields dropped on the pages, keyed to SignatoryRow.key. */
  protected readonly fields = signal<PlacedField[]>([]);
  protected readonly stepError = signal<string | null>(null);

  /** Inline upload of a new PDF on the first step. */
  protected readonly uploadFile = signal<File | null>(null);
  protected readonly uploading = signal(false);
  protected readonly uploadError = signal<string | null>(null);

  /** Emails of the organisation's users, when the sender may list them (for the inbox hint). */
  private readonly memberEmails = signal<Set<string>>(new Set());

  protected readonly recipients = computed<PlacerRecipient[]>(() =>
    this.draft().signatories.map((row, i) => ({
      key: row.key,
      name: row.fullName.trim() || row.emailAddress.trim(),
      color: RECIPIENT_COLOURS[i % RECIPIENT_COLOURS.length],
    })),
  );

  /** Documents that can still be sent for signing, from a private store instance. */
  private readonly documents = injectLocalStore(DocumentApiStore);
  protected readonly eligibleDocuments = computed(() =>
    this.documents.dataList().filter((doc) => !!doc.status && ELIGIBLE.has(doc.status)),
  );

  protected readonly draft = signal<WorkflowDraft>(newDraft());
  protected readonly workflowForm = form(this.draft, workflowDraftSchema, {
    submission: { action: () => this.submit() },
  });

  protected readonly ExecutionMode = ExecutionMode;
  protected readonly SignatoryType = SignatoryType;
  protected readonly maxSignatories = MAX_SIGNATORIES;
  protected readonly verificationMethods: { value: VerificationMethod; label: string }[] = [
    { value: VerificationMethod.EMAIL_LINK, label: 'Email link' },
    { value: VerificationMethod.EMAIL_OTP, label: 'Email one-time code' },
    { value: VerificationMethod.SMS_OTP, label: 'SMS one-time code' },
    { value: VerificationMethod.EXTERNAL_IDENTITY_PROVIDER, label: 'External identity provider' },
  ];
  protected readonly signatoryTypes: { value: SignatoryType; label: string }[] = [
    { value: SignatoryType.EXTERNAL_PERSON, label: 'External person' },
    {
      value: SignatoryType.EXTERNAL_ORGANISATION_REPRESENTATIVE,
      label: 'Organisation representative',
    },
  ];

  /** Progress of the multi-request submission, shown on the submit button. */
  protected readonly submitStep = signal<string | null>(null);
  protected readonly validationIssues = signal<string[]>([]);
  protected readonly formError = computed(
    () =>
      this.workflowForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  protected readonly selectedDocument = computed(
    () => this.documents.dataList().find((doc) => doc.id === this.draft().documentId) ?? null,
  );
  protected readonly invalidSectionCount = computed(() => {
    const f = this.workflowForm;
    return [
      f.documentId().invalid() || f.title().invalid() || f.message().invalid(),
      f.expiresAt().invalid() || f.reminderIntervalHours().invalid() || f.captureTyped().invalid(),
      f.signatories().invalid(),
    ].filter(Boolean).length;
  });

  constructor() {
    this.documents.search({ includeArchived: false });
    void this.loadMembers();

    // Preselect the document from the query string.
    effect(() => {
      const documentId = this.documentId();
      if (documentId) {
        untracked(() => this.workflowForm.documentId().value.set(documentId));
      }
    });

    // Default the workflow title to the chosen document's title while it is untouched.
    effect(() => {
      const doc = this.selectedDocument();
      untracked(() => {
        const title = this.workflowForm.title();
        if (doc && !title.dirty()) {
          title.value.set(`Signature request: ${doc.title}`);
        }
      });
    });
  }

  protected colourAt(index: number): string {
    return RECIPIENT_COLOURS[index % RECIPIENT_COLOURS.length];
  }

  /** Whether the email belongs to a user of the organisation (they also see it in their inbox). */
  protected isMember(email: string): boolean {
    return this.memberEmails().has(email.trim().toLowerCase());
  }

  protected fieldCount(key: string): number {
    return this.fields().filter((f) => f.recipientKey === key).length;
  }

  protected fieldSummary(key: string): string {
    const counts = new Map<string, number>();
    for (const f of this.fields().filter((x) => x.recipientKey === key)) {
      const label = fieldKind(f.type).label;
      counts.set(label, (counts.get(label) ?? 0) + 1);
    }
    return [...counts].map(([label, n]) => (n > 1 ? `${n} × ${label}` : label)).join(', ') || 'No fields';
  }

  protected goTo(step: Step): void {
    const target = STEPS.findIndex((s) => s.id === step);
    if (target <= this.reached()) {
      this.stepError.set(null);
      this.step.set(step);
    }
  }

  protected back(): void {
    const index = this.stepIndex();
    if (index > 0) {
      this.stepError.set(null);
      this.step.set(STEPS[index - 1].id);
    }
  }

  /** Checks the current step and moves on; returns false when it isn't complete. */
  protected next(): boolean {
    const f = this.workflowForm;
    this.stepError.set(null);
    switch (this.step()) {
      case 'document':
        f.documentId().markAsTouched();
        f.title().markAsTouched();
        f.message().markAsTouched();
        if (f.documentId().invalid() || f.title().invalid() || f.message().invalid()) {
          this.stepError.set('Choose or upload a document and give the request a title.');
          return false;
        }
        break;
      case 'recipients': {
        f.signatories().markAsTouched();
        for (const row of f.signatories) {
          for (const key of ['fullName', 'emailAddress', 'phoneNumber', 'organisationName'] as const) {
            row[key]().markAsTouched();
          }
        }
        const settings = [f.expiresAt, f.reminderIntervalHours, f.captureTyped];
        settings.forEach((field) => field().markAsTouched());
        if (f.signatories().invalid() || settings.some((field) => field().invalid())) {
          this.stepError.set('Complete each recipient and the request settings.');
          return false;
        }
        break;
      }
      case 'fields': {
        const missing = this.recipients().filter(
          (r) => !this.fields().some((x) => x.recipientKey === r.key && x.type === 'SIGNATURE'),
        );
        if (missing.length) {
          this.stepError.set(
            `Add a signature field for ${missing.map((r) => r.name || 'every recipient').join(', ')}.`,
          );
          return false;
        }
        break;
      }
      case 'review':
        return true;
    }
    const nextIndex = this.stepIndex() + 1;
    this.step.set(STEPS[nextIndex].id);
    this.reached.update((r) => Math.max(r, nextIndex));
    window.scrollTo({ top: 0, behavior: 'smooth' });
    return true;
  }

  protected async uploadDocument(): Promise<void> {
    const file = this.uploadFile();
    if (!file) {
      return;
    }
    if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) {
      this.uploadError.set('Only PDF documents can be signed.');
      return;
    }
    this.uploading.set(true);
    this.uploadError.set(null);
    try {
      const title = file.name.replace(/\.pdf$/i, '').replace(/[_-]+/g, ' ').trim() || 'Document';
      const document = await firstValueFrom(this.documentApi.upload(file, { title }));
      this.uploadFile.set(null);
      this.documents.search({ includeArchived: false });
      this.workflowForm.documentId().value.set(document.id ?? '');
      toast.success('Document uploaded', { description: document.title ?? undefined });
    } catch (error) {
      this.uploadError.set(apiErrorMessage(error, 'The upload failed.'));
    } finally {
      this.uploading.set(false);
    }
  }

  private async loadMembers(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId || !hasPermission(['users:list'], this.session.permissions())) {
      return;
    }
    try {
      const users = await firstValueFrom(this.organisationApi.findUsers(organisationId));
      this.memberEmails.set(
        new Set(users.map((u) => (u.emailAddress ?? '').toLowerCase()).filter(Boolean)),
      );
    } catch {
      // Only a hint: without it the API still links members by email.
    }
  }

  protected addSignatory(): void {
    this.draft.update((d) => ({ ...d, signatories: [...d.signatories, newSignatory()] }));
  }

  protected removeSignatory(index: number): void {
    this.draft.update((d) => ({ ...d, signatories: d.signatories.filter((_, i) => i !== index) }));
  }

  protected moveSignatory(index: number, offset: -1 | 1): void {
    this.draft.update((d) => {
      const signatories = [...d.signatories];
      const target = index + offset;
      if (target < 0 || target >= signatories.length) {
        return d;
      }
      [signatories[index], signatories[target]] = [signatories[target], signatories[index]];
      return { ...d, signatories };
    });
  }

  /**
   * Submission action: creates the workflow, adds the signatories in routing order and,
   * when requested, validates and sends it. Returning errors marks the form invalid.
   */
  private async submit() {
    // Enter in a field submits the form: on the earlier steps that means "next".
    if (this.step() !== 'review') {
      this.next();
      return undefined;
    }
    const draft = this.draft();
    this.validationIssues.set([]);
    let workflowId: string | undefined;
    try {
      this.submitStep.set('Creating workflow…');
      const workflow = await firstValueFrom(this.workflowApi.create(toCreateWorkflow(draft)));
      workflowId = workflow.id ?? undefined;
      if (!workflowId) {
        throw new Error('The service did not return the new workflow.');
      }

      const signatoryIds = new Map<string, string>();
      for (const [index, row] of draft.signatories.entries()) {
        this.submitStep.set(`Adding recipient ${index + 1} of ${draft.signatories.length}…`);
        const signatory = await firstValueFrom(
          this.workflowApi.addSignatory(workflowId, toSignatory(draft, row, index)),
        );
        if (signatory.id) {
          signatoryIds.set(row.key, signatory.id);
        }
      }

      this.submitStep.set('Placing fields…');
      const pages: DocumentPageDTO[] = await firstValueFrom(this.documentApi.getPages(draft.documentId));
      await firstValueFrom(
        this.workflowApi.saveFields(workflowId, toSigningFields(this.fields(), signatoryIds, pages)),
      );

      if (draft.sendNow) {
        this.submitStep.set('Validating routing…');
        const validation = await firstValueFrom(this.workflowApi.validate(workflowId));
        if (!validation.valid) {
          this.validationIssues.set(
            (validation.issues ?? []).map(
              (issue) => issue.message || issue.code || 'Invalid routing.',
            ),
          );
          toast.warning('Workflow saved as draft', {
            description: 'Fix the routing issues and send it from the workflow page.',
          });
          this.router.navigate(['/workflows', workflowId]);
          return undefined;
        }
        this.submitStep.set('Sending invitations…');
        await firstValueFrom(
          this.workflowApi.initiate(workflowId, {
            idempotencyKey: crypto.randomUUID(),
            sendInvitations: true,
          }),
        );
        toast.success('Sent for signing', {
          description: `${draft.signatories.length} signatory invitation(s) issued.`,
        });
      } else {
        toast.success('Draft workflow saved');
      }
      this.router.navigate(['/workflows', workflowId]);
      return undefined;
    } catch (error) {
      if (workflowId) {
        // The workflow exists; continue from its page rather than creating a duplicate.
        toast.error('Workflow created with errors', { description: apiErrorMessage(error) });
        this.router.navigate(['/workflows', workflowId]);
        return undefined;
      }
      return {
        kind: 'server',
        message: apiErrorMessage(error, 'The workflow could not be created.'),
      };
    } finally {
      this.submitStep.set(null);
    }
  }
}
