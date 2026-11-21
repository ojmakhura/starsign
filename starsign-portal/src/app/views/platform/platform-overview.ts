import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { hasPermission, PAGE_PERMISSIONS, PermissionRequirement } from '@auth/page-permissions';
import { SessionStore } from '@store/session.store';

interface PlatformArea {
  label: string;
  description: string;
  icon: string;
  link: string;
  permissions: PermissionRequirement;
}

const AREAS: PlatformArea[] = [
  {
    label: 'Organisations',
    description: 'Search tenants, approve, suspend or close them and see their usage.',
    icon: 'bi-buildings',
    link: 'organisations',
    permissions: PAGE_PERMISSIONS.platformOrganisations,
  },
  {
    label: 'Statistics',
    description: 'Platform-wide activity: organisations, documents, workflows and signatures.',
    icon: 'bi-graph-up',
    link: 'statistics',
    permissions: PAGE_PERMISSIONS.platformStatistics,
  },
  {
    label: 'Plans',
    description: 'Subscription plans, prices and the limits each plan allows.',
    icon: 'bi-tags',
    link: 'plans',
    permissions: PAGE_PERMISSIONS.platformPlans,
  },
  {
    label: 'Configuration',
    description: 'System settings and the security policy applied to every tenant.',
    icon: 'bi-sliders',
    link: 'configuration',
    permissions: PAGE_PERMISSIONS.platformConfiguration,
  },
  {
    label: 'Notification providers',
    description: 'Email and SMS delivery providers, their priority and rate limits.',
    icon: 'bi-broadcast',
    link: 'notification-providers',
    permissions: PAGE_PERMISSIONS.platformProviders,
  },
  {
    label: 'Storage',
    description: 'Reconciliation issues, reconciliation runs and failed uploads.',
    icon: 'bi-hdd-stack',
    link: 'storage',
    permissions: PAGE_PERMISSIONS.platformStorage,
  },
  {
    label: 'Platform branding',
    description: 'The default look, logos and support details every portal inherits.',
    icon: 'bi-palette',
    link: 'branding',
    permissions: PAGE_PERMISSIONS.platformBranding,
  },
];

/** Landing page of /platform: a card for each administration area the user can open. */
@Component({
  selector: 'app-platform-overview',
  imports: [RouterLink],
  template: `
    <div class="page">
      <header class="page-header">
        <div>
          <nav class="breadcrumb" aria-label="Breadcrumb">
            <a routerLink="/">Command</a><i class="bi bi-chevron-right" aria-hidden="true"></i
            ><span class="breadcrumb__current">Platform</span>
          </nav>
          <h1 class="page-title">Platform administration</h1>
          <p class="page-subtitle">
            Manage tenants and platform-wide settings. Document contents are never visible here.
          </p>
        </div>
      </header>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        @for (area of areas(); track area.link) {
          <a class="card flex flex-col gap-2 no-underline hover:border-cyan" [routerLink]="area.link">
            <i class="bi text-2xl text-cyan" [class]="area.icon" aria-hidden="true"></i>
            <span class="text-headline-sm text-ink">{{ area.label }}</span>
            <span class="text-body-sm text-ink-muted">{{ area.description }}</span>
          </a>
        } @empty {
          <div class="empty-state card">
            <i class="bi bi-shield-lock" aria-hidden="true"></i>
            <span>You have no platform administration permissions.</span>
          </div>
        }
      </div>
    </div>
  `,
})
export class PlatformOverview {
  private readonly session = inject(SessionStore);
  protected readonly areas = computed(() =>
    AREAS.filter((area) => hasPermission(area.permissions, this.session.permissions())),
  );
}
