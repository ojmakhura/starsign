import { Routes } from '@angular/router';

export const reportRoutes: Routes = [
  {
    path: '',
    title: 'Reports · StarSign',
    loadComponent: () => import('./reports').then((m) => m.Reports),
  },
];
