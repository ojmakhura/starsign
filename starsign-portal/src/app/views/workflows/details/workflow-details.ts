import { DatePipe } from '@angular/common';
import { HttpClient, httpResource } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { form, FormField, FormRoot, maxLength, minLength, required } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import {
  SignatoryDTO,
  SignatoryStatus,
  SignatoryType,
  SigningWorkflowDTO,
  WorkflowStatus,
  WorkflowTimelineEntryDTO,
} from '@models/workflow';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom } from 'rxjs';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { StatusChip } from '@shared/status-chip/status-chip';
import { injectLocalStore } from '@store/local-store';
import { LoaderState } from '@store/loader-state';
import { StoreRequest } from '@store/store-request';
import { WorkflowApiStore } from '@store/workflow/workflow-api.store';
import { toast } from 'ngx-sonner';

/** Signatories who were invited but have not acted yet: their invitation can be sent again. */
const AWAITING = new Set<SignatoryStatus>([
  SignatoryStatus.INVITED,
  SignatoryStatus.VIEWED,
  SignatoryStatus.VERIFIED,
]);

const OPEN = new Set<WorkflowStatus>([
  WorkflowStatus.DRAFT,
  WorkflowStatus.READY,
  WorkflowStatus.IN_PROGRESS,
]);

@Component({
  selector: 'app-workflow-details',
  imports: [Can, DatePipe, FieldErrors, FormField, FormRoot, RouterLink, StatusChip],
  templateUrl: './workflow-details.html',
})
export class WorkflowDetails {
  readonly id = input.required<string>();

  protected readonly workflow = httpResource<SigningWorkflowDTO>(() => `/workflows/${this.id()}`);
  protected readonly timeline = httpResource<WorkflowTimelineEntryDTO[]>(
    () => `/workflows/${this.id()}/timeline`,
    {
      defaultValue: [],
    },
  );
  protected readonly wf = computed(() => (this.workflow.hasValue() ? this.workflow.value() : null));

  /** Signatories in routing order (stage, then signing order). */
  protected readonly signatories = computed(() =>
    (this.wf()?.stages ?? [])
      .flatMap((stage) =>
        (stage.signatories ?? []).map((s) => ({
          ...s,
          stageNumber: s.stageNumber ?? stage.stageNumber,
        })),
      )
      .sort(
        (a, b) =>
          (a.stageNumber ?? 0) - (b.stageNumber ?? 0) ||
          (a.signingOrder ?? 0) - (b.signingOrder ?? 0),
      ),
  );
  protected readonly SignatoryType = SignatoryType;
  protected readonly resending = signal<string | null>(null);
  private readonly http = inject(HttpClient);

  protected canResend(signatory: SignatoryDTO): boolean {
    return (
      this.wf()?.status === WorkflowStatus.IN_PROGRESS &&
      !!signatory.status &&
      AWAITING.has(signatory.status)
    );
  }

  /**
   * Sends the invitation again (POST /workflows/{id}/signatories/{signatoryId}/resend): a new
   * link and expiry for external signatories; earlier links stop working. The generated
   * WorkflowApi has no method for it because the endpoint returns no body.
   */
  protected async resend(signatory: SignatoryDTO): Promise<void> {
    if (!signatory.id || !confirm(`Send the invitation to ${signatory.emailAddress} again?`)) {
      return;
    }
    this.resending.set(signatory.id);
    try {
      await firstValueFrom(
        this.http.post<void>(`/workflows/${this.id()}/signatories/${signatory.id}/resend`, {}),
      );
      toast.success('Invitation sent again', { description: signatory.emailAddress ?? undefined });
      this.workflow.reload();
      this.timeline.reload();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The invitation could not be sent again.'));
    } finally {
      this.resending.set(null);
    }
  }

  /** The signatory a reminder is being sent to. */
  protected readonly reminding = signal<string | null>(null);

  /**
   * Reminds the signatory now (POST /workflows/{id}/signatories/{signatoryId}/remind) instead of
   * waiting for the schedule; the API refuses while an earlier reminder is recent.
   */
  protected remind(signatory: SignatoryDTO): void {
    const signatoryId = signatory.id;
    if (!signatoryId) {
      return;
    }
    this.reminding.set(signatoryId);
    this.request.run('remind', () => this.actions.sendReminder({ id: this.id(), signatoryId }));
  }

  protected readonly canSend = computed(() => {
    const status = this.wf()?.status;
    return status === WorkflowStatus.DRAFT || status === WorkflowStatus.READY;
  });
  protected readonly canCancel = computed(() => OPEN.has(this.wf()?.status as WorkflowStatus));
  protected readonly sealed = computed(() => this.wf()?.status === WorkflowStatus.COMPLETED);

  /** Days left before expiry, live from the loaded workflow. */
  protected readonly daysLeft = computed(() => {
    const expires = this.wf()?.expiresAt;
    return expires ? Math.ceil((new Date(expires).getTime() - Date.now()) / 86_400_000) : null;
  });

  // Actions run through a private store; StoreRequest turns its signals into done/error.
  private readonly actions = injectLocalStore(WorkflowApiStore);
  protected readonly request = new StoreRequest<'initiate' | 'cancel' | 'remind'>(this.actions, {
    done: {
      initiate: () => this.afterAction('Sent for signing', 'Invitations are on their way.'),
      remind: () => {
        const name = this.signatories().find((s) => s.id === this.reminding())?.fullName;
        this.reminding.set(null);
        toast.success('Reminder sent', {
          description: `${name || 'The signatory'} has been reminded to sign.`,
        });
      },
      cancel: () => {
        this.cancelOpen.set(false);
        this.afterAction('Workflow cancelled', 'Signatories can no longer sign.');
      },
    },
    onError: (action) => {
      if (action === 'remind') {
        this.reminding.set(null);
      }
    },
  });

  // Cancel dialog — its own small signal form.
  protected readonly cancelOpen = signal(false);
  private readonly cancellation = signal({ reason: '', notifySignatories: true });
  protected readonly cancelForm = form(
    this.cancellation,
    (path) => {
      required(path.reason, {
        message: 'Record why the workflow is cancelled — it goes into the audit trail.',
      });
      minLength(path.reason, 10, { message: 'Give at least 10 characters.' });
      maxLength(path.reason, 500);
    },
    {
      submission: {
        action: async () => {
          this.request.run('cancel', () =>
            this.actions.cancel({
              id: this.id(),
              cancellation: {
                reason: this.cancellation().reason.trim(),
                notifySignatories: this.cancellation().notifySignatories,
              },
            }),
          );
          return undefined;
        },
      },
    },
  );

  constructor() {
    inject(LoaderState).track(
      () => this.workflow.isLoading() || this.request.busy(),
      'Loading workflow…',
    );
  }

  protected send(): void {
    this.request.run('initiate', () =>
      this.actions.initiate({
        id: this.id(),
        initiation: { idempotencyKey: crypto.randomUUID(), sendInvitations: true },
      }),
    );
  }

  protected openCancel(): void {
    this.cancelForm().reset({ reason: '', notifySignatories: true });
    this.cancelOpen.set(true);
  }

  /** The event's text: the API stores audit details as JSON ({"text": …}), so unwrap that. */
  protected describe(entry: WorkflowTimelineEntryDTO): string {
    const description = entry.description?.trim();
    if (description?.startsWith('{')) {
      try {
        const details = JSON.parse(description) as Record<string, unknown>;
        if (typeof details['text'] === 'string') {
          return details['text'];
        }
      } catch {
        // Not JSON after all: show it as it is
      }
    }
    return description || (entry.eventType ?? '').replaceAll('_', ' ');
  }

  protected nodeClass(entry: WorkflowTimelineEntryDTO): string {
    const type = entry.eventType ?? '';
    if (/SEAL|COMPLET/i.test(type)) {
      return 'timeline__node--sealed';
    }
    if (/DECLIN|CANCEL|EXPIR|REVOK|FAIL/i.test(type)) {
      return 'timeline__node--void';
    }
    return /SIGNED|VERIFIED|SENT|INITIAT/i.test(type) ? 'timeline__node--done' : '';
  }

  private afterAction(title: string, description: string): void {
    toast.success(title, { description });
    this.workflow.reload();
    this.timeline.reload();
  }
}
