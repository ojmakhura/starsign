import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { form, FormField, FormRoot, maxLength, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import { hasPermission } from '@auth/page-permissions';
import { AddressDTO, OrganisationDTO, OrganisationStatus } from '@models/organisation';
import { ReconciliationRunDTO, StorageUsageDTO } from '@models/storage';
import { SubscriptionDTO, UsageMetric, UsageSummaryDTO } from '@models/subscription';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { StorageAdminApi } from '@services/storage/storage-admin-api';
import { SubscriptionApi } from '@services/subscription/subscription-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { formatBytes, formatLimit, humanise, toDateInput } from '../platform-format';
import { ORGANISATION_TRANSITIONS, organisationTone } from './organisation-status';

interface StatusChangeModel {
  status: OrganisationStatus | '';
  reason: string;
}

/** What each target status means, shown in the status dialog. */
const STATUS_EFFECTS: Partial<Record<OrganisationStatus, string>> = {
  [OrganisationStatus.ACTIVE]: 'Users can sign in, upload documents and start workflows.',
  [OrganisationStatus.SUSPENDED]:
    'Uploads and new workflows are blocked until the organisation is reactivated.',
  [OrganisationStatus.INACTIVE]: 'The organisation is dormant; it can be reactivated later.',
  [OrganisationStatus.CLOSED]: 'Closing is permanent: the organisation can never be reopened.',
};

/**
 * One organisation as a platform administrator sees it: profile, status (activate,
 * suspend, deactivate, close), storage usage and subscription. Never document contents.
 */
@Component({
  selector: 'app-platform-organisation-details',
  imports: [Can, DatePipe, DecimalPipe, FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './platform-organisation-details.html',
})
export class PlatformOrganisationDetails implements OnInit {
  /** Route parameter. */
  readonly id = input.required<string>();

  private readonly organisationApi = inject(OrganisationApi);
  private readonly storageApi = inject(StorageAdminApi);
  private readonly subscriptionApi = inject(SubscriptionApi);
  private readonly session = inject(SessionStore);

  protected readonly humanise = humanise;
  protected readonly tone = organisationTone;
  protected readonly formatBytes = formatBytes;
  protected readonly formatLimit = formatLimit;
  protected readonly statusEffects = STATUS_EFFECTS;
  protected readonly activeStatus = OrganisationStatus.ACTIVE;

  protected readonly organisation = signal<OrganisationDTO | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly usage = signal<StorageUsageDTO | null>(null);
  protected readonly usageError = signal<string | null>(null);
  protected readonly subscription = signal<SubscriptionDTO | null>(null);
  protected readonly usageSummary = signal<UsageSummaryDTO | null>(null);
  protected readonly subscriptionError = signal<string | null>(null);
  protected readonly reconciliation = signal<ReconciliationRunDTO | null>(null);
  protected readonly reconciling = signal(false);

  protected readonly canSeeStorage = computed(() =>
    hasPermission(['admin-storage:view'], this.session.permissions()),
  );
  protected readonly canSeeSubscription = computed(() =>
    hasPermission(['subscriptions:view'], this.session.permissions()),
  );

  protected readonly transitions = computed(() => {
    const status = this.organisation()?.status;
    return status ? ORGANISATION_TRANSITIONS[status] : [];
  });

  protected readonly storageBreakdown = computed(() => {
    const usage = this.usage();
    if (!usage) {
      return [];
    }
    const total = usage.totalBytes ?? 0;
    const parts = [
      { label: 'Originals', bytes: usage.originalsBytes ?? 0 },
      { label: 'Signed artifacts', bytes: usage.artifactsBytes ?? 0 },
      { label: 'Previews', bytes: usage.previewBytes ?? 0 },
      { label: 'Evidence', bytes: usage.evidenceBytes ?? 0 },
    ];
    const other = total - parts.reduce((sum, part) => sum + part.bytes, 0);
    if (other > 0) {
      parts.push({ label: 'Other', bytes: other });
    }
    return parts.map((part) => ({ ...part, percent: total ? (part.bytes / total) * 100 : 0 }));
  });

  // ---- Status change dialog ----
  protected readonly statusOpen = signal(false);
  protected readonly statusModel = signal<StatusChangeModel>({ status: '', reason: '' });
  protected readonly statusForm = form(
    this.statusModel,
    (path) => {
      validate(path.status, ({ value }) =>
        value() ? null : { kind: 'required', message: 'Choose the new status.' },
      );
      validate(path.reason, ({ value, valueOf }) =>
        valueOf(path.status) && valueOf(path.status) !== OrganisationStatus.ACTIVE && !value().trim()
          ? { kind: 'required', message: 'Give the reason for this change; it is recorded in the audit trail.' }
          : null,
      );
      maxLength(path.reason, 500, { message: 'Keep the reason under 500 characters.' });
    },
    {
      submission: {
        action: async () => {
          const organisation = this.organisation();
          const { status, reason } = this.statusModel();
          if (!organisation?.id || !status) {
            return undefined;
          }
          if (
            status === OrganisationStatus.CLOSED &&
            !confirm(`Close ${organisation.name}? This is permanent and cannot be undone.`)
          ) {
            return undefined;
          }
          try {
            const updated = await firstValueFrom(
              this.organisationApi.changeStatus(organisation.id, {
                status,
                reason: reason.trim() || null,
              }),
            );
            this.organisation.set(updated);
            this.statusOpen.set(false);
            toast.success('Status changed', {
              description: `${updated.name} is now ${humanise(updated.status)}.`,
            });
            return undefined;
          } catch (error) {
            return {
              kind: 'server',
              message: apiErrorMessage(error, 'The status could not be changed.'),
            };
          }
        },
      },
    },
  );
  protected readonly statusServerError = computed(
    () =>
      this.statusForm()
        .errors()
        .find((error) => error.kind === 'server')?.message ?? null,
  );

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.organisation.set(await firstValueFrom(this.organisationApi.findById(this.id())));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the organisation.'));
      this.loading.set(false);
      return;
    }
    this.loading.set(false);
    await Promise.all([this.loadUsage(), this.loadSubscription()]);
  }

  protected openStatus(status?: OrganisationStatus): void {
    this.statusModel.set({ status: status ?? this.transitions()[0] ?? '', reason: '' });
    this.statusForm().reset();
    this.statusOpen.set(true);
  }

  protected async runReconciliation(): Promise<void> {
    const organisation = this.organisation();
    if (!organisation?.id) {
      return;
    }
    this.reconciling.set(true);
    try {
      const run = await firstValueFrom(this.storageApi.runReconciliation(organisation.id));
      this.reconciliation.set(run);
      toast.success('Reconciliation finished', {
        description: `${run.issuesDetected ?? 0} issues detected, ${run.issuesResolved ?? 0} resolved.`,
      });
      await this.loadUsage();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The reconciliation failed.'));
    } finally {
      this.reconciling.set(false);
    }
  }

  protected clampPercent(value: number | null | undefined): number {
    return Math.max(0, Math.min(100, value ?? 0));
  }

  protected metricLabel(metric: UsageMetric | null | undefined): string {
    return humanise(metric);
  }

  protected metricValue(metric: UsageMetric | null | undefined, value: number | null | undefined): string {
    if (metric === UsageMetric.STORAGE_BYTES) {
      return value === null || value === undefined ? 'Unlimited' : formatBytes(value);
    }
    return formatLimit(value);
  }

  protected address(address: AddressDTO | null | undefined): string {
    if (!address) {
      return '—';
    }
    return (
      [address.line1, address.line2, address.city, address.postalCode, address.country]
        .filter(Boolean)
        .join(', ') || '—'
    );
  }

  private async loadUsage(): Promise<void> {
    if (!this.canSeeStorage()) {
      return;
    }
    this.usageError.set(null);
    try {
      this.usage.set(await firstValueFrom(this.storageApi.getUsage(this.id())));
    } catch (error) {
      this.usageError.set(apiErrorMessage(error, 'Unable to load the storage usage.'));
    }
  }

  private async loadSubscription(): Promise<void> {
    if (!this.canSeeSubscription()) {
      return;
    }
    this.subscriptionError.set(null);
    const now = new Date();
    const from = toDateInput(new Date(now.getFullYear(), now.getMonth(), 1));
    const to = toDateInput(now);
    const [subscription, summary] = await Promise.allSettled([
      firstValueFrom(this.subscriptionApi.getSubscription(this.id())),
      firstValueFrom(this.subscriptionApi.getUsageSummary(this.id(), from, to)),
    ]);
    if (subscription.status === 'fulfilled') {
      this.subscription.set(subscription.value);
    } else if (!(subscription.reason instanceof HttpErrorResponse && subscription.reason.status === 404)) {
      this.subscriptionError.set(apiErrorMessage(subscription.reason, 'Unable to load the subscription.'));
    }
    if (summary.status === 'fulfilled') {
      this.usageSummary.set(summary.value);
    }
  }
}
