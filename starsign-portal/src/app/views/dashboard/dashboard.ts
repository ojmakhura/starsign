import { DatePipe, DecimalPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { hasPermission, PAGE_PERMISSIONS } from '@auth/page-permissions';
import { Can } from '@auth/can.directive';
import { DashboardSummaryDTO } from '@models/reporting';
import { StatusChip } from '@shared/status-chip/status-chip';
import { SessionStore } from '@store/session.store';
import { InternalSigningApiStore } from '@store/signing/internal-signing-api.store';

@Component({
  selector: 'app-dashboard',
  imports: [Can, DatePipe, DecimalPipe, RouterLink, StatusChip],
  templateUrl: './dashboard.html',
})
export class Dashboard {
  protected readonly session = inject(SessionStore);
  protected readonly signing = inject(InternalSigningApiStore);

  private readonly canViewReports = computed(() =>
    hasPermission(PAGE_PERMISSIONS.reports, this.session.permissions()),
  );

  /** Re-fetches whenever the organisation (or the user's report access) changes. */
  protected readonly summary = httpResource<DashboardSummaryDTO>(() => {
    const organisationId = this.session.organisationId();
    return organisationId && this.canViewReports()
      ? `/reports/dashboard/${organisationId}`
      : undefined;
  });

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const part = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
    const name = this.session.profile()?.firstName;
    return name ? `${part}, ${name}` : part;
  });

  protected readonly tiles = computed(() => {
    const s = this.summary.hasValue() ? this.summary.value() : null;
    return [
      {
        label: 'Awaiting you',
        value: this.signing.pendingCount(),
        sub: `${this.signing.dueSoon().length} due within 48h`,
        icon: 'bi-pen',
        gold: false,
      },
      {
        label: 'Awaiting signatures',
        value: s?.awaitingSignatures ?? null,
        sub: `${s?.partiallySigned ?? 0} partially signed`,
        icon: 'bi-hourglass-split',
        gold: false,
      },
      {
        label: 'Sealed & completed',
        value: s?.completed ?? null,
        sub: `of ${s?.totalDocuments ?? 0} documents`,
        icon: 'bi-patch-check-fill',
        gold: true,
      },
      {
        label: 'Declined / expired',
        value: s ? (s.declined ?? 0) + (s.expired ?? 0) : null,
        sub: `${s?.cancelledWorkflows ?? 0} workflows cancelled`,
        icon: 'bi-x-octagon',
        gold: false,
      },
    ];
  });

  protected readonly completionRate = computed(() => {
    const s = this.summary.hasValue() ? this.summary.value() : null;
    const completed = s?.completed ?? 0;
    const finished = s ? completed + (s.declined ?? 0) + (s.expired ?? 0) : 0;
    return finished ? Math.round((completed / finished) * 100) : null;
  });
}
