import { Routes } from '@angular/router';

/**
 * The public signing portal (mounted at /sign, data.public): external signatories open
 * /sign/{token} from their invitation email without a StarSign account.
 */
export const externalSigningRoutes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Sign a Document · StarSign',
    loadComponent: () => import('./sign-link-missing').then((m) => m.SignLinkMissing),
  },
  {
    path: ':token',
    title: 'Sign Document · StarSign',
    loadComponent: () => import('./sign-portal').then((m) => m.SignPortal),
  },
];
