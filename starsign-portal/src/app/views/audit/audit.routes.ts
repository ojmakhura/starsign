import { Routes } from '@angular/router';

export const auditRoutes: Routes = [
  {
    path: '',
    title: 'Audit Trail · StarSign',
    loadComponent: () => import('./audit').then((m) => m.Audit),
  },
];
