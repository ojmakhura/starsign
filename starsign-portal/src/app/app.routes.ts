import { Routes } from '@angular/router';
import { authGuard } from '@auth/auth.guard';
import { PAGE_PERMISSIONS } from '@auth/page-permissions';
import { permissionGuard } from '@auth/permission.guard';
import { signedInGuard } from '@auth/signed-in.guard';

/**
 * Every page sits behind Keycloak (authGuard) and its permission scopes (permissionGuard),
 * except the public ones (data.public, see auth/public-pages.ts): organisation sign-up
 * and accepting an invitation, which comes before the invitee has any role.
 */
export const routes: Routes = [
  {
    path: 'register',
    title: 'Register Organisation · StarSign',
    data: { public: true },
    loadComponent: () =>
      import('./views/register/organisation-register').then((m) => m.OrganisationRegister),
  },
  {
    path: 'invitations/accept',
    title: 'Accept Invitation · StarSign',
    data: { public: true },
    canActivate: [signedInGuard],
    loadComponent: () =>
      import('./views/invitations/invitation-accept').then((m) => m.InvitationAccept),
  },
  {
    path: 'sign',
    data: { public: true },
    loadChildren: () =>
      import('./views/external-signing/external-signing.routes').then((m) => m.externalSigningRoutes),
  },
  {
    path: '',
    canActivateChild: [authGuard, permissionGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Dashboard · StarSign',
        data: { permissions: PAGE_PERMISSIONS.dashboard },
        loadComponent: () => import('./views/dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'signing',
        data: { permissions: PAGE_PERMISSIONS.signing },
        loadChildren: () => import('./views/signing/signing.routes').then((m) => m.signingRoutes),
      },
      {
        path: 'documents',
        data: { permissions: PAGE_PERMISSIONS.documents },
        loadChildren: () =>
          import('./views/documents/documents.routes').then((m) => m.documentRoutes),
      },
      {
        path: 'workflows',
        data: { permissions: PAGE_PERMISSIONS.workflows },
        loadChildren: () =>
          import('./views/workflows/workflows.routes').then((m) => m.workflowRoutes),
      },
      {
        path: 'users',
        data: { permissions: PAGE_PERMISSIONS.users },
        loadChildren: () => import('./views/users/users.routes').then((m) => m.userRoutes),
      },
      {
        path: 'organisation',
        data: { permissions: PAGE_PERMISSIONS.organisation },
        loadChildren: () => import('./views/organisation/organisation.routes').then((m) => m.organisationRoutes),
      },
      {
        path: 'roles',
        data: { permissions: PAGE_PERMISSIONS.roles },
        loadChildren: () => import('./views/roles/roles.routes').then((m) => m.roleRoutes),
      },
      {
        path: 'notification-templates',
        data: { permissions: PAGE_PERMISSIONS.notificationTemplates },
        loadChildren: () => import('./views/notification-templates/notification-templates.routes').then((m) => m.notificationTemplateRoutes),
      },
      {
        path: 'templates',
        data: { permissions: PAGE_PERMISSIONS.templates },
        loadChildren: () => import('./views/templates/templates.routes').then((m) => m.templateRoutes),
      },
      {
        path: 'reports',
        data: { permissions: PAGE_PERMISSIONS.reports },
        loadChildren: () => import('./views/reports/reports.routes').then((m) => m.reportRoutes),
      },
      {
        path: 'audit',
        data: { permissions: PAGE_PERMISSIONS.audit },
        loadChildren: () => import('./views/audit/audit.routes').then((m) => m.auditRoutes),
      },
      {
        path: 'notifications',
        data: { permissions: PAGE_PERMISSIONS.notifications },
        loadChildren: () => import('./views/notifications/notifications.routes').then((m) => m.notificationRoutes),
      },
      {
        path: 'subscription',
        data: { permissions: PAGE_PERMISSIONS.subscription },
        loadChildren: () => import('./views/subscription/subscription.routes').then((m) => m.subscriptionRoutes),
      },
      {
        path: 'profile',
        data: { permissions: PAGE_PERMISSIONS.profile },
        loadChildren: () => import('./views/profile/profile.routes').then((m) => m.profileRoutes),
      },
      {
        path: 'platform',
        data: { permissions: PAGE_PERMISSIONS.platform },
        loadChildren: () => import('./views/platform/platform.routes').then((m) => m.platformRoutes),
      },
      {
        path: 'verify',
        title: 'Verify Document · StarSign',
        data: { permissions: PAGE_PERMISSIONS.verify },
        loadComponent: () => import('./views/verify/verify').then((m) => m.Verify),
      },
      { path: '**', redirectTo: '' },
    ],
  },
];
