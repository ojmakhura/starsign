import { ActivatedRouteSnapshot, CanActivateFn, RouterStateSnapshot } from '@angular/router';
import { AuthGuardData, createAuthGuard } from 'keycloak-angular';

/**
 * Requires a Keycloak session but, unlike authGuard, not the portal role: an invitee
 * signs in before accepting, and only gets their roles once they have accepted.
 */
const isSignedIn = async (
  _route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
  { authenticated, keycloak }: AuthGuardData,
): Promise<boolean> => {
  if (authenticated) {
    return true;
  }
  await keycloak.login({ redirectUri: window.location.origin + state.url });
  return false;
};

export const signedInGuard: CanActivateFn = createAuthGuard<CanActivateFn>(isSignedIn);
