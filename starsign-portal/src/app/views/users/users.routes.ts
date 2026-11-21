import { Routes } from '@angular/router';
import { PAGE_PERMISSIONS } from '@auth/page-permissions';

export const userRoutes: Routes = [
  {
    path: '',
    title: 'Users · StarSign',
    loadComponent: () => import('./users').then((m) => m.Users),
  },
  {
    path: 'invite',
    title: 'Invite User · StarSign',
    data: { permissions: PAGE_PERMISSIONS.userInvite },
    loadComponent: () => import('./invite/user-invite').then((m) => m.UserInvite),
  },
  {
    path: ':id',
    title: 'User · StarSign',
    data: { permissions: ['users:view'] },
    loadComponent: () => import('./detail/user-detail').then((m) => m.UserDetail),
  },
];
