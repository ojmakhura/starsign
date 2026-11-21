import { inject, Injectable } from '@angular/core';
import { EnvLoaderService } from '@services/env-loader.service';
import Keycloak from 'keycloak-js';
import { isPublicPath } from './public-pages';

/**
 * Restricts the portal to users holding the portal client role (PORTAL_USER on the
 * starsign-portal client, granted through STARSIGN_USER). Users without it are logged
 * out. Checked by App as soon as login completes and by the route guards. The public
 * pages (sign-up, accepting an invitation) are exempt.
 */
@Injectable({ providedIn: 'root' })
export class PortalAccessService {
  private readonly keycloak = inject(Keycloak);
  private readonly envLoader = inject(EnvLoaderService);

  /** True if the current user may use the portal; otherwise logs them out and returns false. */
  ensureAccess(): boolean {
    if (!this.keycloak.authenticated || isPublicPath(window.location.pathname)) {
      return true;
    }
    const role = this.envLoader.env()?.portalRole;
    if (!role) {
      return true;
    }
    const clientId = this.envLoader.env()?.clientId || this.keycloak.clientId;
    if (this.keycloak.hasResourceRole(role, clientId) || this.keycloak.hasRealmRole(role)) {
      return true;
    }
    console.warn(`Signed-in user lacks the "${role}" role on ${clientId}; signing out.`);
    this.keycloak.logout({ redirectUri: this.envLoader.env()?.redirectUri || window.location.origin });
    return false;
  }
}
