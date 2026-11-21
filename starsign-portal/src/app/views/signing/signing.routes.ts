import { Routes } from '@angular/router';

export const signingRoutes: Routes = [
  {
    path: '',
    title: 'Signing Inbox · StarSign',
    loadComponent: () => import('./signing-inbox').then((m) => m.SigningInbox),
  },
  {
    // The link in signing notifications (SigningLinks.internalSigningLink in starsign-api)
    path: 'workflows/:workflowId/signatories/:signatoryId',
    title: 'Sign Document · StarSign',
    loadComponent: () => import('./sign-document').then((m) => m.SignDocument),
  },
  {
    // Short form kept for links already shared
    path: ':workflowId/:signatoryId',
    title: 'Sign Document · StarSign',
    loadComponent: () => import('./sign-document').then((m) => m.SignDocument),
  },
];
