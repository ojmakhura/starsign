import { Routes } from '@angular/router';
import { PAGE_PERMISSIONS } from '@auth/page-permissions';

/**
 * Platform administration (PLATFORM_ADMIN). Mounted at /platform behind any platform
 * scope; each page needs its own. Platform administrators have no organisation and
 * never see document contents.
 */
export const platformRoutes: Routes = [
  {
    path: '',
    title: 'Platform · StarSign',
    loadComponent: () => import('./platform-overview').then((m) => m.PlatformOverview),
  },
  {
    path: 'organisations',
    title: 'Organisations · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformOrganisations },
    loadComponent: () =>
      import('./organisations/platform-organisations').then((m) => m.PlatformOrganisations),
  },
  {
    path: 'organisations/:id',
    title: 'Organisation · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformOrganisations },
    loadComponent: () =>
      import('./organisations/platform-organisation-details').then(
        (m) => m.PlatformOrganisationDetails,
      ),
  },
  {
    path: 'statistics',
    title: 'Platform Statistics · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformStatistics },
    loadComponent: () =>
      import('./statistics/platform-statistics').then((m) => m.PlatformStatistics),
  },
  {
    path: 'plans',
    title: 'Subscription Plans · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformPlans },
    loadComponent: () => import('./plans/platform-plans').then((m) => m.PlatformPlans),
  },
  {
    path: 'configuration',
    title: 'Platform Configuration · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformConfiguration },
    loadComponent: () =>
      import('./configuration/platform-configuration').then((m) => m.PlatformConfiguration),
  },
  {
    path: 'notification-providers',
    title: 'Notification Providers · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformProviders },
    loadComponent: () =>
      import('./notification-providers/platform-providers').then((m) => m.PlatformProviders),
  },
  {
    path: 'storage',
    title: 'Storage · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformStorage },
    loadComponent: () => import('./storage/platform-storage').then((m) => m.PlatformStorage),
  },
  {
    path: 'branding',
    title: 'Platform Branding · StarSign',
    data: { permissions: PAGE_PERMISSIONS.platformBranding },
    loadComponent: () => import('./branding/platform-branding').then((m) => m.PlatformBranding),
  },
];
