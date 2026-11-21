import { Routes } from '@angular/router';

export const roleRoutes: Routes = [
  {
    path: '',
    title: 'Roles · StarSign',
    loadComponent: () => import('./roles').then((m) => m.Roles),
  },
  {
    path: 'new',
    title: 'New Role · StarSign',
    data: { permissions: ['roles:edit'] },
    loadComponent: () => import('./form/role-form').then((m) => m.RoleForm),
  },
  {
    path: ':id',
    title: 'Role · StarSign',
    loadComponent: () => import('./detail/role-detail').then((m) => m.RoleDetail),
  },
  {
    path: ':id/edit',
    title: 'Edit Role · StarSign',
    data: { permissions: ['roles:edit'] },
    loadComponent: () => import('./form/role-form').then((m) => m.RoleForm),
  },
];
