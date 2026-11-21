import { DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { form, FormField, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { OrganisationStatus } from '@models/organisation';
import { PlatformStatisticsDTO } from '@models/reporting';
import { ReportingApi } from '@services/reporting/reporting-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { saveFile, toCsv } from '@shared/save-file';
import { apiErrorMessage } from '@store/app-state';
import { firstValueFrom } from 'rxjs';
import { daysAgo, formatBytes, humanise, toDateInput } from '../platform-format';
import { BarDatum, BarListChart, ColumnChart } from './bar-charts';

interface Period {
  from: string;
  to: string;
}

interface MonthStatistics {
  label: string;
  from: string;
  to: string;
  stats: PlatformStatisticsDTO;
}

type ActivityKey =
  | 'documentsUploaded'
  | 'workflowsInitiated'
  | 'workflowsCompleted'
  | 'signaturesCompleted'
  | 'notificationsFailed';

const ACTIVITY: { key: ActivityKey; label: string }[] = [
  { key: 'documentsUploaded', label: 'Documents uploaded' },
  { key: 'workflowsInitiated', label: 'Workflows initiated' },
  { key: 'workflowsCompleted', label: 'Workflows completed' },
  { key: 'signaturesCompleted', label: 'Signatures completed' },
];

const TREND_MONTHS = 6;

/** Platform-wide statistics (GET /reports/platform) for a period, plus a six-month trend. */
@Component({
  selector: 'app-platform-statistics',
  imports: [BarListChart, ColumnChart, DecimalPipe, FieldErrors, FormField, RouterLink],
  templateUrl: './platform-statistics.html',
})
export class PlatformStatistics {
  private readonly reportingApi = inject(ReportingApi);

  protected readonly activity = ACTIVITY;
  protected readonly formatBytes = formatBytes;
  protected readonly presets = [7, 30, 90, 365];
  protected readonly today = toDateInput(new Date());

  protected readonly period = signal<Period>({ from: daysAgo(29), to: this.today });
  protected readonly periodForm = form(this.period, (path) => {
    validate(path.from, ({ value }) =>
      value() ? null : { kind: 'required', message: 'Choose the start date.' },
    );
    validate(path.to, ({ value, valueOf }) => {
      if (!value()) {
        return { kind: 'required', message: 'Choose the end date.' };
      }
      return valueOf(path.from) && value() < valueOf(path.from)
        ? { kind: 'dateRange', message: 'The end date is before the start date.' }
        : null;
    });
  });

  protected readonly stats = signal<PlatformStatisticsDTO | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly months = signal<MonthStatistics[]>([]);
  protected readonly trendLoading = signal(true);
  protected readonly trendError = signal<string | null>(null);

  protected readonly organisationTotal = computed(() =>
    this.organisationsByStatus().reduce((sum, d) => sum + d.value, 0),
  );

  protected readonly organisationsByStatus = computed<BarDatum[]>(() => {
    const byStatus = this.stats()?.organisationsByStatus ?? {};
    return Object.values(OrganisationStatus).map((status) => ({
      label: humanise(status),
      value: Number(byStatus[status] ?? 0),
    }));
  });

  protected readonly completionRate = computed(() => {
    const s = this.stats();
    const initiated = s?.workflowsInitiated ?? 0;
    return initiated ? Math.round(((s?.workflowsCompleted ?? 0) / initiated) * 100) : null;
  });

  protected readonly trend = computed(() =>
    ACTIVITY.map((metric) => {
      const data = this.months().map((m) => ({
        label: m.label,
        value: m.stats[metric.key] ?? 0,
      }));
      const total = data.reduce((sum, d) => sum + d.value, 0);
      return { ...metric, data, summary: `${total.toLocaleString()} in ${data.length} months` };
    }),
  );

  private requestId = 0;

  constructor() {
    effect(() => {
      const period = this.period();
      if (this.periodForm().valid()) {
        untracked(() => void this.load(period));
      }
    });
    void this.loadTrend();
  }

  protected preset(days: number): void {
    this.period.set({ from: daysAgo(days - 1), to: this.today });
  }

  protected isPreset(days: number): boolean {
    const p = this.period();
    return p.to === this.today && p.from === daysAgo(days - 1);
  }

  protected exportCsv(): void {
    const s = this.stats();
    if (!s) {
      return;
    }
    const { from, to } = this.period();
    const rows: unknown[][] = [
      ['Metric', 'Value'],
      ['Period', `${from} to ${to}`],
      ['Active users', s.activeUsers ?? 0],
      ...ACTIVITY.map((m) => [m.label, s[m.key] ?? 0]),
      ['Notifications failed', s.notificationsFailed ?? 0],
      ['Storage bytes', s.storageBytes ?? 0],
      ...this.organisationsByStatus().map((d) => [`Organisations ${d.label.toLowerCase()}`, d.value]),
    ];
    saveFile(toCsv(rows), `platform-statistics-${from}-${to}.csv`);
  }

  private async load(period: Period): Promise<void> {
    const id = ++this.requestId;
    this.loading.set(true);
    this.error.set(null);
    try {
      const stats = await firstValueFrom(this.reportingApi.platformStatistics(period.from, period.to));
      if (id === this.requestId) {
        this.stats.set(stats);
      }
    } catch (error) {
      if (id === this.requestId) {
        this.error.set(apiErrorMessage(error, 'Unable to load the platform statistics.'));
      }
    } finally {
      if (id === this.requestId) {
        this.loading.set(false);
      }
    }
  }

  /** One request per calendar month, the current (partial) month last. */
  private async loadTrend(): Promise<void> {
    const now = new Date();
    const monthLabel = new Intl.DateTimeFormat(undefined, { month: 'short', year: '2-digit' });
    const ranges = Array.from({ length: TREND_MONTHS }, (_, i) => {
      const start = new Date(now.getFullYear(), now.getMonth() - (TREND_MONTHS - 1 - i), 1);
      const end = new Date(start.getFullYear(), start.getMonth() + 1, 0);
      return {
        label: monthLabel.format(start),
        from: toDateInput(start),
        to: toDateInput(end > now ? now : end),
      };
    });
    try {
      const results = await Promise.all(
        ranges.map((r) => firstValueFrom(this.reportingApi.platformStatistics(r.from, r.to))),
      );
      this.months.set(ranges.map((r, i) => ({ ...r, stats: results[i] })));
    } catch (error) {
      this.trendError.set(apiErrorMessage(error, 'Unable to load the monthly trend.'));
    } finally {
      this.trendLoading.set(false);
    }
  }
}
