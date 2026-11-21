import { Routes } from '@angular/router';

/**
 * Notification templates (/notification-templates, gated by notification-templates:list): the
 * organisation's own templates and the platform defaults they override.
 */
export const notificationTemplateRoutes: Routes = [
  {
    path: '',
    title: 'Email templates · StarSign',
    loadComponent: () => import('./notification-templates').then((m) => m.NotificationTemplates),
  },
  {
    path: 'new',
    title: 'New template · StarSign',
    data: { permissions: ['notification-templates:edit'] },
    loadComponent: () =>
      import('./editor/notification-template-editor').then((m) => m.NotificationTemplateEditor),
  },
  {
    path: ':id',
    title: 'Template · StarSign',
    data: { permissions: ['notification-templates:view'] },
    loadComponent: () =>
      import('./editor/notification-template-editor').then((m) => m.NotificationTemplateEditor),
  },
];
