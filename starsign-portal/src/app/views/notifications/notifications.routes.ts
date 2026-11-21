import { Routes } from '@angular/router';

export const notificationRoutes: Routes = [
  {
    path: '',
    title: 'Notifications · StarSign',
    loadComponent: () => import('./notifications').then((m) => m.Notifications),
  },
  {
    path: 'organisation',
    title: 'Organisation Notifications · StarSign',
    data: { permissions: ['notifications:list'] },
    loadComponent: () =>
      import('./organisation/organisation-notifications').then((m) => m.OrganisationNotifications),
  },
];
