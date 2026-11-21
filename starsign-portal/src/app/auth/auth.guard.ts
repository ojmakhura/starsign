import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, RouterStateSnapshot } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';
import { PortalAccessService } from './portal-access.service';

/**
 * Keycloak protection for every page. Signed-out visitors on the landing page see the
 * shell's sign-in card; any deeper link sends them straight to Keycloak and back.
 * Signed-in users without the portal role are logged out.
 */
const isAccessAllowed = async (
  _route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
  authData: AuthGuardData,
): Promise<boolean> => {
  const { authenticated, keycloak } = authData;

  if (!authenticated) {
    // Compare the path only: after a failed Keycloak callback the URL still carries
    // "#state=...&code=...", and redirecting from there would loop.
    const path = state.url.split(/[?#]/)[0];
    if (path === '/' || path === '') {
      return true;
    }
    await keycloak.login({ redirectUri: window.location.origin + state.url });
    return false;
  }

  return inject(PortalAccessService).ensureAccess();
};

export const authGuard: CanActivateFn = createAuthGuard<CanActivateFn>(isAccessAllowed);
