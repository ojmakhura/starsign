import { Routes } from '@angular/router';

export const templateRoutes: Routes = [
  {
    path: '',
    title: 'Signing Templates · StarSign',
    loadComponent: () => import('./templates').then((m) => m.Templates),
  },
  {
    path: 'new',
    title: 'New Template · StarSign',
    data: { permissions: ['templates:edit'] },
    loadComponent: () => import('./edit/template-edit').then((m) => m.TemplateEdit),
  },
  {
    path: 'from-workflow/:workflowId',
    title: 'Template from Workflow · StarSign',
    data: { permissions: ['templates:edit'] },
    loadComponent: () =>
      import('./from-workflow/template-from-workflow').then((m) => m.TemplateFromWorkflow),
  },
  {
    path: ':id',
    title: 'Template · StarSign',
    data: { permissions: ['templates:view'] },
    loadComponent: () => import('./details/template-details').then((m) => m.TemplateDetails),
  },
  {
    path: ':id/edit',
    title: 'Edit Template · StarSign',
    data: { permissions: ['templates:edit'] },
    loadComponent: () => import('./edit/template-edit').then((m) => m.TemplateEdit),
  },
  {
    path: ':id/use',
    title: 'Use Template · StarSign',
    data: { permissions: ['templates:initiate'] },
    loadComponent: () =>
      import('./instantiate/template-instantiate').then((m) => m.TemplateInstantiate),
  },
];
