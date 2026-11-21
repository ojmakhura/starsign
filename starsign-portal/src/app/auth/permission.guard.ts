import { effect, inject, Injector } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from '@store/session.store';
import Keycloak from 'keycloak-js';
import { hasPermission, PermissionRequirement } from './page-permissions';

/**
 * Allows the route only when the signed-in user holds one of the permission scopes in
 * `data.permissions` (see PAGE_PERMISSIONS); otherwise redirects to the dashboard.
 * Waits for the user's permissions (GET /users/me) on the first navigation.
 * Runs after authGuard, so signed-out visitors never get here past the landing page.
 */
export const permissionGuard: CanActivateFn = async (route) => {
  if (!inject(Keycloak).authenticated) {
    return true;
  }

  const router = inject(Router);
  const session = inject(SessionStore);
  const injector = inject(Injector);
  const required = route.data['permissions'] as PermissionRequirement | undefined;

  if (!session.permissionsLoaded()) {
    if (!session.loading()) {
      session.loadCurrentUser();
    }
    await new Promise<void>((resolve) => {
      const watcher = effect(
        () => {
          if (session.permissionsLoaded()) {
            watcher.destroy();
            resolve();
          }
        },
        { injector },
      );
    });
  }

  return hasPermission(required, session.permissions()) || router.createUrlTree(['/']);
};
