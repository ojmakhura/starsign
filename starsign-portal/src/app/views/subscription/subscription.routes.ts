import { Routes } from '@angular/router';

export const subscriptionRoutes: Routes = [
  {
    path: '',
    title: 'Subscription · StarSign',
    loadComponent: () => import('./subscription').then((m) => m.Subscription),
  },
];
