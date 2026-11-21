import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  form,
  FormField,
  FormRoot,
  max,
  maxLength,
  min,
  pattern,
  readonly,
  required,
} from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { SubscriptionPlanDTO } from '@models/subscription';
import { SubscriptionApi } from '@services/subscription/subscription-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { apiErrorMessage } from '@store/app-state';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { formatBytes, formatLimit } from '../platform-format';

interface PlanModel {
  code: string;
  name: string;
  description: string;
  currency: string;
  monthlyPrice: number | null;
  annualPrice: number | null;
  maxUsers: number | null;
  maxDocumentsPerMonth: number | null;
  maxWorkflowsPerMonth: number | null;
  /** Edited in GB; the API stores bytes. */
  maxStorageGb: number | null;
  maxSmsPerMonth: number | null;
  trialDays: number | null;
  digitalSignaturesEnabled: boolean;
  templatesEnabled: boolean;
  apiAccessEnabled: boolean;
  active: boolean;
  publicPlan: boolean;
}

const GB = 1024 ** 3;

const EMPTY_PLAN: PlanModel = {
  code: '',
  name: '',
  description: '',
  currency: 'BWP',
  monthlyPrice: 0,
  annualPrice: 0,
  maxUsers: null,
  maxDocumentsPerMonth: null,
  maxWorkflowsPerMonth: null,
  maxStorageGb: null,
  maxSmsPerMonth: null,
  trialDays: 0,
  digitalSignaturesEnabled: false,
  templatesEnabled: false,
  apiAccessEnabled: false,
  active: true,
  publicPlan: true,
};

/** Subscription plans: prices and limits (POST /subscriptions/plans creates or updates one). */
@Component({
  selector: 'app-platform-plans',
  imports: [DecimalPipe, FieldErrors, FormField, FormRoot, RouterLink],
  templateUrl: './platform-plans.html',
})
export class PlatformPlans implements OnInit {
  private readonly subscriptionApi = inject(SubscriptionApi);

  protected readonly formatBytes = formatBytes;
  protected readonly formatLimit = formatLimit;

  protected readonly plans = signal<SubscriptionPlanDTO[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** The plan being edited; null with `editorOpen` for a new plan. */
  protected readonly editing = signal<SubscriptionPlanDTO | null>(null);
  protected readonly editorOpen = signal(false);

  protected readonly model = signal<PlanModel>({ ...EMPTY_PLAN });
  protected readonly planForm = form(
    this.model,
    (path) => {
      required(path.code, { message: 'Give the plan a code.' });
      pattern(path.code, /^[A-Za-z0-9_-]{1,50}$/, {
        message: "Up to 50 letters, digits, '-' or '_'.",
      });
      readonly(path.code, () => this.editing() !== null);
      required(path.name, { message: 'Give the plan a name.' });
      maxLength(path.name, 100);
      maxLength(path.description, 1000);
      required(path.currency, { message: 'Give the currency.' });
      pattern(path.currency, /^[A-Za-z]{3}$/, { message: 'An ISO 4217 code such as BWP.' });
      required(path.monthlyPrice, { message: 'Give the monthly price.' });
      min(path.monthlyPrice, 0, { message: 'The price cannot be negative.' });
      required(path.annualPrice, { message: 'Give the annual price.' });
      min(path.annualPrice, 0, { message: 'The price cannot be negative.' });
      min(path.maxUsers, 1, { message: 'A plan must allow at least one user.' });
      min(path.maxDocumentsPerMonth, 0, { message: 'Limits cannot be negative.' });
      min(path.maxWorkflowsPerMonth, 0, { message: 'Limits cannot be negative.' });
      min(path.maxStorageGb, 0, { message: 'Limits cannot be negative.' });
      min(path.maxSmsPerMonth, 0, { message: 'Limits cannot be negative.' });
      min(path.trialDays, 0, { message: 'Trials last 0 to 365 days.' });
      max(path.trialDays, 365, { message: 'Trials last 0 to 365 days.' });
    },
    {
      submission: {
        action: async () => {
          try {
            const saved = await firstValueFrom(this.subscriptionApi.savePlan(this.toPlan()));
            toast.success(this.editing() ? 'Plan saved' : 'Plan created', {
              description: `${saved.name} (${saved.code})`,
            });
            this.editorOpen.set(false);
            await this.load();
            return undefined;
          } catch (error) {
            return { kind: 'server', message: apiErrorMessage(error, 'The plan could not be saved.') };
          }
        },
      },
    },
  );
  protected readonly serverError = computed(
    () =>
      this.planForm()
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
      const plans = await firstValueFrom(this.subscriptionApi.findPlans(true));
      this.plans.set(
        [...plans].sort(
          (a, b) =>
            Number(b.active ?? true) - Number(a.active ?? true) ||
            (a.monthlyPrice ?? 0) - (b.monthlyPrice ?? 0),
        ),
      );
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the plans.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected create(): void {
    this.editing.set(null);
    this.model.set({ ...EMPTY_PLAN });
    this.planForm().reset();
    this.editorOpen.set(true);
  }

  protected edit(plan: SubscriptionPlanDTO): void {
    this.editing.set(plan);
    this.model.set({
      code: plan.code ?? '',
      name: plan.name ?? '',
      description: plan.description ?? '',
      currency: plan.currency ?? '',
      monthlyPrice: plan.monthlyPrice ?? 0,
      annualPrice: plan.annualPrice ?? 0,
      maxUsers: plan.maxUsers ?? null,
      maxDocumentsPerMonth: plan.maxDocumentsPerMonth ?? null,
      maxWorkflowsPerMonth: plan.maxWorkflowsPerMonth ?? null,
      maxStorageGb:
        plan.maxStorageBytes === null || plan.maxStorageBytes === undefined
          ? null
          : Math.round((plan.maxStorageBytes / GB) * 100) / 100,
      maxSmsPerMonth: plan.maxSmsPerMonth ?? null,
      trialDays: plan.trialDays ?? 0,
      digitalSignaturesEnabled: !!plan.digitalSignaturesEnabled,
      templatesEnabled: !!plan.templatesEnabled,
      apiAccessEnabled: !!plan.apiAccessEnabled,
      active: plan.active ?? true,
      publicPlan: plan.publicPlan ?? true,
    });
    this.planForm().reset();
    this.editorOpen.set(true);
  }

  protected close(): void {
    this.editorOpen.set(false);
  }

  private toPlan(): SubscriptionPlanDTO {
    const m = this.model();
    const editing = this.editing();
    return {
      ...(editing ?? {}),
      code: m.code.trim().toUpperCase(),
      name: m.name.trim(),
      description: m.description.trim() || null,
      currency: m.currency.trim().toUpperCase(),
      monthlyPrice: m.monthlyPrice,
      annualPrice: m.annualPrice,
      maxUsers: m.maxUsers,
      maxDocumentsPerMonth: m.maxDocumentsPerMonth,
      maxWorkflowsPerMonth: m.maxWorkflowsPerMonth,
      maxStorageBytes: m.maxStorageGb === null ? null : Math.round(m.maxStorageGb * GB),
      maxSmsPerMonth: m.maxSmsPerMonth,
      trialDays: m.trialDays ?? 0,
      digitalSignaturesEnabled: m.digitalSignaturesEnabled,
      templatesEnabled: m.templatesEnabled,
      apiAccessEnabled: m.apiAccessEnabled,
      active: m.active,
      publicPlan: m.publicPlan,
    };
  }
}
