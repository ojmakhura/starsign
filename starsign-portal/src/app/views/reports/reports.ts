import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { Can } from '@auth/can.directive';
import {
  DashboardSummaryDTO,
  DocumentVolumeRowDTO,
  ReportCriteria,
  ReportFormat,
  ReportGrouping,
  ReportType,
  SignatoryActivityRowDTO,
  SigningPerformanceDTO,
  UserActivityRowDTO,
} from '@models/reporting';
import { StorageUsageDTO } from '@models/storage';
import { ReportingApi } from '@services/reporting/reporting-api';
import { FieldErrors } from '@shared/field-errors/field-errors';
import { saveFile } from '@shared/save-file';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';
import { dateRange, toDateInput } from '../documents/search-dates';
import { ChartSeries, TrendChart } from './trend-chart';

type ReportTab = Exclude<ReportType, ReportType.PLATFORM_STATISTICS>;

interface ReportFilter {
  from: string;
  to: string;
  grouping: ReportGrouping;
}

const TOP_N = 10;

/** Organisation reporting: dashboard KPIs plus period reports with charts, tables and export. */
@Component({
  selector: 'app-reports',
  imports: [
    Can,
    DatePipe,
    DecimalPipe,
    FieldErrors,
    FormField,
    PercentPipe,
    RouterLink,
    TrendChart,
  ],
  templateUrl: './reports.html',
})
export class Reports {
  private readonly reportingApi = inject(ReportingApi);
  private readonly session = inject(SessionStore);

  protected readonly ReportType = ReportType;
  protected readonly groupings = Object.values(ReportGrouping);
  protected readonly tabs: { label: string; type: ReportTab }[] = [
    { label: 'Document volume', type: ReportType.DOCUMENT_VOLUME },
    { label: 'Signing performance', type: ReportType.SIGNING_PERFORMANCE },
    { label: 'Signatory activity', type: ReportType.SIGNATORY_ACTIVITY },
    { label: 'User activity', type: ReportType.USER_ACTIVITY },
    { label: 'Storage', type: ReportType.STORAGE_UTILISATION },
  ];

  protected readonly organisationId = this.session.organisationId;
  protected readonly tab = signal<ReportTab>(ReportType.DOCUMENT_VOLUME);

  protected readonly filter = signal<ReportFilter>({
    from: toDateInput(new Date(Date.now() - 29 * 86_400_000)),
    to: toDateInput(new Date()),
    grouping: ReportGrouping.DAY,
  });
  protected readonly filterForm = form(this.filter, (path) => dateRange(path.from, path.to));

  protected readonly dashboard = signal<DashboardSummaryDTO | null>(null);
  protected readonly volume = signal<DocumentVolumeRowDTO[]>([]);
  protected readonly performance = signal<SigningPerformanceDTO | null>(null);
  protected readonly signatories = signal<SignatoryActivityRowDTO[]>([]);
  protected readonly userActivity = signal<UserActivityRowDTO[]>([]);
  protected readonly storage = signal<StorageUsageDTO | null>(null);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly exporting = signal<ReportFormat | null>(null);

  private readonly criteria = computed<ReportCriteria | null>(() => {
    const organisationId = this.organisationId();
    if (!organisationId || this.filterForm().invalid()) {
      return null;
    }
    const f = this.filter();
    return { organisationId, from: f.from || null, to: f.to || null, grouping: f.grouping };
  });

  /** API periods: day 2026-11-19 -> 19-11-2026, month 2026-11 -> 11-2026; ISO weeks (2026-W47) as they are. */
  protected periodLabel(period: string | null | undefined): string {
    const value = period ?? '';
    const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (day) {
      return `${day[3]}-${day[2]}-${day[1]}`;
    }
    const month = /^(\d{4})-(\d{2})$/.exec(value);
    return month ? `${month[2]}-${month[1]}` : value;
  }

  // Chart inputs ---------------------------------------------------------------

  protected readonly volumeLabels = computed(() =>
    this.volume().map((r) => this.periodLabel(r.period)),
  );
  protected readonly volumeSeries = computed<ChartSeries[]>(() => [
    {
      name: 'Created',
      color: 'var(--color-secondary)',
      values: this.volume().map((r) => r.created ?? 0),
    },
    { name: 'Signed', color: 'var(--color-cyan)', values: this.volume().map((r) => r.signed ?? 0) },
    {
      name: 'Declined',
      color: 'var(--color-crimson)',
      values: this.volume().map((r) => r.declined ?? 0),
    },
  ]);

  protected readonly performanceRows = computed(() => this.performance()?.rows ?? []);
  protected readonly performanceLabels = computed(() =>
    this.performanceRows().map((r) => this.periodLabel(r.period)),
  );
  protected readonly performanceSeries = computed<ChartSeries[]>(() => [
    {
      name: 'Sent',
      color: 'var(--color-secondary)',
      values: this.performanceRows().map((r) => r.sent ?? 0),
    },
    {
      name: 'Signed',
      color: 'var(--color-cyan)',
      values: this.performanceRows().map((r) => r.signed ?? 0),
    },
  ]);

  private readonly topSignatories = computed(() =>
    [...this.signatories()].sort((a, b) => (b.invited ?? 0) - (a.invited ?? 0)).slice(0, TOP_N),
  );
  protected readonly signatoryLabels = computed(() =>
    this.topSignatories().map((r) => r.signatoryName || r.signatoryEmail || '—'),
  );
  protected readonly signatorySeries = computed<ChartSeries[]>(() => [
    {
      name: 'Invited',
      color: 'var(--color-secondary)',
      values: this.topSignatories().map((r) => r.invited ?? 0),
    },
    {
      name: 'Signed',
      color: 'var(--color-cyan)',
      values: this.topSignatories().map((r) => r.signed ?? 0),
    },
  ]);

  private readonly topUsers = computed(() =>
    [...this.userActivity()]
      .sort((a, b) => (b.workflowsInitiated ?? 0) - (a.workflowsInitiated ?? 0))
      .slice(0, TOP_N),
  );
  protected readonly userLabels = computed(() => this.topUsers().map((r) => r.userName || '—'));
  protected readonly userSeries = computed<ChartSeries[]>(() => [
    {
      name: 'Documents uploaded',
      color: 'var(--color-secondary)',
      values: this.topUsers().map((r) => r.documentsUploaded ?? 0),
    },
    {
      name: 'Workflows initiated',
      color: 'var(--color-cyan)',
      values: this.topUsers().map((r) => r.workflowsInitiated ?? 0),
    },
  ]);

  protected readonly storageRows = computed(() => {
    const s = this.storage();
    return s
      ? [
          { label: 'Originals', bytes: s.originalsBytes ?? 0 },
          { label: 'Signed artifacts', bytes: s.artifactsBytes ?? 0 },
          { label: 'Previews', bytes: s.previewBytes ?? 0 },
          { label: 'Evidence', bytes: s.evidenceBytes ?? 0 },
        ]
      : [];
  });
  protected readonly storageLabels = computed(() => this.storageRows().map((r) => r.label));
  protected readonly storageSeries = computed<ChartSeries[]>(() => [
    {
      name: 'MB',
      color: 'var(--color-cyan)',
      values: this.storageRows().map((r) => +(r.bytes / 1_048_576).toFixed(2)),
    },
  ]);

  constructor() {
    void this.loadDashboard();
    // Reload the active report whenever the tab or a valid filter changes.
    effect(() => {
      const tab = this.tab();
      const criteria = this.criteria();
      if (criteria) {
        untracked(() => void this.loadReport(tab, criteria));
      }
    });
  }

  protected selectTab(tab: ReportTab): void {
    this.tab.set(tab);
  }

  private async loadDashboard(): Promise<void> {
    const organisationId = this.organisationId();
    if (!organisationId) {
      this.error.set('Reports are kept per organisation and your account is not linked to one.');
      return;
    }
    try {
      this.dashboard.set(await firstValueFrom(this.reportingApi.getDashboard(organisationId)));
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the dashboard.'));
    }
  }

  private async loadReport(tab: ReportTab, criteria: ReportCriteria): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      switch (tab) {
        case ReportType.DOCUMENT_VOLUME:
          this.volume.set(await firstValueFrom(this.reportingApi.documentVolume(criteria)));
          break;
        case ReportType.SIGNING_PERFORMANCE:
          this.performance.set(
            await firstValueFrom(this.reportingApi.signingPerformance(criteria)),
          );
          break;
        case ReportType.SIGNATORY_ACTIVITY:
          this.signatories.set(await firstValueFrom(this.reportingApi.signatoryActivity(criteria)));
          break;
        case ReportType.USER_ACTIVITY:
          this.userActivity.set(await firstValueFrom(this.reportingApi.userActivity(criteria)));
          break;
        case ReportType.STORAGE_UTILISATION:
          this.storage.set(
            await firstValueFrom(this.reportingApi.storageUtilisation(criteria.organisationId!)),
          );
          break;
      }
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'Unable to load the report.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async export(format: 'CSV' | 'PDF'): Promise<void> {
    const criteria = this.criteria();
    if (!criteria) {
      return;
    }
    const reportFormat = format === 'CSV' ? ReportFormat.CSV : ReportFormat.PDF;
    this.exporting.set(reportFormat);
    try {
      const blob = await firstValueFrom(
        this.reportingApi.export(this.tab(), reportFormat, criteria),
      );
      const name = this.tab().toLowerCase().replaceAll('_', '-');
      saveFile(blob, `${name}-${criteria.from ?? ''}-${criteria.to ?? ''}.${format.toLowerCase()}`);
    } catch (error) {
      toast.error('Export failed', { description: apiErrorMessage(error) });
    } finally {
      this.exporting.set(null);
    }
  }

  protected megabytes(bytes?: number | null): number {
    return (bytes ?? 0) / 1_048_576;
  }
}
