import { Routes } from '@angular/router';

/**
 * Organisation settings (/organisation, gated by organisations:view). Every tab is readable by
 * organisation viewers; editing needs organisations:edit (profile), organisations:configure
 * (signing policy, notifications) or branding:configure (branding).
 */
export const organisationRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./organisation-settings').then((m) => m.OrganisationSettings),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'profile' },
      {
        path: 'profile',
        title: 'Organisation profile · StarSign',
        loadComponent: () =>
          import('./profile/organisation-profile').then((m) => m.OrganisationProfile),
      },
      {
        path: 'signing-policy',
        title: 'Signing policy · StarSign',
        loadComponent: () =>
          import('./signing-policy/signing-policy').then((m) => m.SigningPolicy),
      },
      {
        path: 'notifications',
        title: 'Notification settings · StarSign',
        loadComponent: () =>
          import('./notification-settings/notification-settings').then(
            (m) => m.NotificationSettings,
          ),
      },
      {
        path: 'branding',
        title: 'Branding · StarSign',
        loadComponent: () =>
          import('./branding/organisation-branding').then((m) => m.OrganisationBranding),
      },
    ],
  },
];
