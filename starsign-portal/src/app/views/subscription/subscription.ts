import { HttpErrorResponse } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import {
  BillingCycle,
  SubscriptionDTO,
  SubscriptionPlanDTO,
  SubscriptionStatus,
  UsageMetric,
  UsageMetricValueDTO,
  UsageSummaryDTO,
} from '@models/subscription';
import { SubscriptionApi } from '@services/subscription/subscription-api';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { toDateInput } from '../documents/search-dates';

const METRIC_LABELS: Record<UsageMetric, string> = {
  [UsageMetric.USERS]: 'Users',
  [UsageMetric.DOCUMENTS_UPLOADED]: 'Documents uploaded',
  [UsageMetric.WORKFLOWS_INITIATED]: 'Workflows initiated',
  [UsageMetric.SIGNATURES_COMPLETED]: 'Signatures completed',
  [UsageMetric.STORAGE_BYTES]: 'Storage',
  [UsageMetric.EMAILS_SENT]: 'Emails sent',
  [UsageMetric.SMS_SENT]: 'SMS sent',
  [UsageMetric.DIGITAL_SIGNATURES]: 'Digital signatures',
  [UsageMetric.API_CALLS]: 'API calls',
};

/** Subscription statuses after which the plan can no longer be changed or cancelled. */
const ENDED = [SubscriptionStatus.CANCELLED, SubscriptionStatus.EXPIRED];

/** The organisation's subscription: plan, usage against its limits, plan changes and cancellation. */
@Component({
  selector: 'app-subscription',
  imports: [Can, CurrencyPipe, DatePipe, RouterLink],
  templateUrl: './subscription.html',
  styles: `
    .meter--near .progress__bar {
      background: var(--color-gold);
      box-shadow: none;
    }
    .meter--over .progress__bar {
      background: var(--color-crimson);
      box-shadow: none;
    }
  `,
})
export class Subscription {
  private readonly subscriptionApi = inject(SubscriptionApi);
  private readonly session = inject(SessionStore);

  protected readonly BillingCycle = BillingCycle;

  protected readonly subscription = signal<SubscriptionDTO | null>(null);
  protected readonly usage = signal<UsageSummaryDTO | null>(null);
  protected readonly plans = signal<SubscriptionPlanDTO[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  /** Billing cycle offered when switching plans. */
  protected readonly cycle = signal<BillingCycle>(BillingCycle.MONTHLY);

  protected readonly currentPlanCode = computed(() => this.subscription()?.plan?.code ?? null);
  protected readonly ended = computed(() => {
    const status = this.subscription()?.status;
    return !!status && ENDED.includes(status);
  });

  protected readonly meters = computed(() =>
    (this.usage()?.metrics ?? []).map((m) => ({
      ...m,
      label: m.metric ? METRIC_LABELS[m.metric] : '—',
      percent: m.limit ? Math.min(100, m.percentage ?? 0) : 0,
      over: (m.percentage ?? 0) >= 100,
      near: (m.percentage ?? 0) >= 80,
    })),
  );

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId) {
      this.loading.set(false);
      this.error.set('Your account is not linked to an organisation.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const [subscription, plans] = await Promise.all([
        // A new organisation has no subscription until it picks a plan (the API answers 404).
        firstValueFrom(this.subscriptionApi.getSubscription(organisationId)).catch((error) => {
          if (error instanceof HttpErrorResponse && error.status === 404) {
            return null;
          }
          throw error;
        }),
        firstValueFrom(this.subscriptionApi.findPlans()),
      ]);
      this.subscription.set(subscription);
      this.plans.set(plans);
      if (!subscription) {
        this.usage.set(null);
        return;
      }
      if (subscription.billingCycle) {
        this.cycle.set(subscription.billingCycle);
      }
      // Usage over the current billing period, or the current month without one.
      const now = new Date();
      const from =
        subscription.currentPeriodStart ??
        toDateInput(new Date(now.getFullYear(), now.getMonth(), 1));
      const to = subscription.currentPeriodEnd ?? toDateInput(now);
      this.usage.set(
        await firstValueFrom(this.subscriptionApi.getUsageSummary(organisationId, from, to)),
      );
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the subscription.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected formatValue(metric: UsageMetricValueDTO, value: number | null | undefined): string {
    if (value == null) {
      return '∞';
    }
    return metric.metric === UsageMetric.STORAGE_BYTES ? this.bytes(value) : value.toLocaleString();
  }

  protected bytes(value: number | null | undefined): string {
    if (value == null) {
      return 'Unlimited';
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let size = value;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }
    return `${size.toLocaleString(undefined, { maximumFractionDigits: 1 })} ${units[unit]}`;
  }

  protected limit(value: number | null | undefined): string {
    return value == null ? 'Unlimited' : value.toLocaleString();
  }

  protected price(plan: SubscriptionPlanDTO): number | null {
    return (this.cycle() === BillingCycle.ANNUAL ? plan.annualPrice : plan.monthlyPrice) ?? null;
  }

  protected isCurrent(plan: SubscriptionPlanDTO): boolean {
    return (
      plan.code === this.currentPlanCode() &&
      this.cycle() === (this.subscription()?.billingCycle ?? BillingCycle.MONTHLY)
    );
  }

  protected async changePlan(plan: SubscriptionPlanDTO): Promise<void> {
    const organisationId = this.session.organisationId();
    if (!organisationId || !plan.code) {
      return;
    }
    const cycle = this.cycle() === BillingCycle.ANNUAL ? 'annually' : 'monthly';
    if (!confirm(`Switch the organisation to the ${plan.name} plan, billed ${cycle}?`)) {
      return;
    }
    this.busy.set(plan.code);
    try {
      const updated = await firstValueFrom(
        this.subscriptionApi.changePlan(organisationId, { planCode: plan.code, billingCycle: this.cycle() }),
      );
      toast.success('Plan changed', { description: `You are now on the ${updated.plan?.name ?? plan.name} plan.` });
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The plan could not be changed.'));
    } finally {
      this.busy.set(null);
    }
  }

  protected async cancel(): Promise<void> {
    const organisationId = this.session.organisationId();
    const subscription = this.subscription();
    if (!organisationId || !subscription) {
      return;
    }
    const until = subscription.currentPeriodEnd ? ` It stays usable until ${subscription.currentPeriodEnd}.` : '';
    if (!confirm(`Cancel the ${subscription.plan?.name ?? ''} subscription? Auto-renewal stops.${until}`)) {
      return;
    }
    this.busy.set('cancel');
    try {
      const updated = await firstValueFrom(this.subscriptionApi.cancel(organisationId));
      toast.success('Subscription cancelled', {
        description: updated.endDate ? `It ends on ${updated.endDate}.` : undefined,
      });
      await this.load();
    } catch (error) {
      toast.error(apiErrorMessage(error, 'The subscription could not be cancelled.'));
    } finally {
      this.busy.set(null);
    }
  }

  protected statusChip(status: SubscriptionStatus | null | undefined): string {
    switch (status) {
      case SubscriptionStatus.ACTIVE:
        return 'chip--sealed';
      case SubscriptionStatus.TRIAL:
        return 'chip--pending';
      case SubscriptionStatus.PAST_DUE:
      case SubscriptionStatus.SUSPENDED:
      case SubscriptionStatus.CANCELLED:
      case SubscriptionStatus.EXPIRED:
        return 'chip--voided';
      default:
        return 'chip--neutral';
    }
  }
}
