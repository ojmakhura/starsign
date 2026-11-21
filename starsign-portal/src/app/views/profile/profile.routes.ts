import { Routes } from '@angular/router';

export const profileRoutes: Routes = [
  {
    path: '',
    title: 'My Profile · StarSign',
    loadComponent: () => import('./profile').then((m) => m.Profile),
  },
];
