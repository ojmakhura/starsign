import { Routes } from '@angular/router';
import { PAGE_PERMISSIONS } from '@auth/page-permissions';

export const documentRoutes: Routes = [
  {
    path: '',
    title: 'Document Vault · StarSign',
    loadComponent: () => import('./documents').then((m) => m.Documents),
  },
  {
    path: 'upload',
    title: 'Upload PDF · StarSign',
    data: { permissions: PAGE_PERMISSIONS.documentUpload },
    loadComponent: () => import('./upload/document-upload').then((m) => m.DocumentUpload),
  },
  {
    path: ':id',
    title: 'Document · StarSign',
    loadComponent: () => import('./details/document-details').then((m) => m.DocumentDetails),
  },
];
