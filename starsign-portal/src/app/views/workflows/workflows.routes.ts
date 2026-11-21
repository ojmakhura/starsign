import { Routes } from '@angular/router';
import { PAGE_PERMISSIONS } from '@auth/page-permissions';

export const workflowRoutes: Routes = [
  {
    path: '',
    title: 'Signing Workflows · StarSign',
    loadComponent: () => import('./workflows').then((m) => m.Workflows),
  },
  {
    path: 'new',
    title: 'New Workflow · StarSign',
    data: { permissions: PAGE_PERMISSIONS.workflowCreate },
    loadComponent: () => import('./create/workflow-create').then((m) => m.WorkflowCreate),
  },
  {
    path: ':id',
    title: 'Workflow · StarSign',
    loadComponent: () => import('./details/workflow-details').then((m) => m.WorkflowDetails),
  },
];
