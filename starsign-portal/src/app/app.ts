import { Component, effect, inject, untracked } from '@angular/core';
import { PortalAccessService } from '@auth/portal-access.service';
import { isPublicPath } from '@auth/public-pages';
import { NotificationInboxService } from '@services/notification/notification-inbox.service';
import { SessionStore } from '@store/session.store';
import { InternalSigningApiStore } from '@store/signing/internal-signing-api.store';
import { KEYCLOAK_EVENT_SIGNAL, KeycloakEventType } from 'keycloak-angular';
import Keycloak from 'keycloak-js';
import { Shell } from './shell/shell';

@Component({
  selector: 'app-root',
  imports: [Shell],
  template: '<app-shell />',
})
export class App {
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakEvent = inject(KEYCLOAK_EVENT_SIGNAL);
  private readonly session = inject(SessionStore);
  private readonly signing = inject(InternalSigningApiStore);
  private readonly portalAccess = inject(PortalAccessService);
  private readonly notificationInbox = inject(NotificationInboxService);

  constructor() {
    // Keycloak lifecycle → session signals. Only the event is tracked: the handlers
    // read session signals that loadCurrentUser() changes, which would otherwise re-run
    // this effect and repeat the requests (e.g. GET /signing/pending twice per load).
    effect(() => {
      const event = this.keycloakEvent();
      untracked(() => this.onKeycloakEvent(event.type));
    });
  }

  private onKeycloakEvent(type: KeycloakEventType): void {
    if (type === KeycloakEventType.Ready && this.keycloak.authenticated) {
      if (!this.portalAccess.ensureAccess()) {
        return;
      }
      this.session.setAuthenticated(true);
      // On a public page the user may not belong to an organisation yet (an invitee
      // before accepting), so the account and inbox are loaded once they leave it.
      if (!isPublicPath(window.location.pathname)) {
        if (!this.session.permissionsLoaded() && !this.session.loading()) {
          this.session.loadCurrentUser();
        }
        this.signing.getPendingForCurrentUser();
        this.notificationInbox.connect();
      }
      this.keycloak.loadUserProfile().then((profile) =>
        this.session.setProfile({
          firstName: profile.firstName ?? '',
          lastName: profile.lastName ?? '',
          email: profile.email ?? '',
          username: profile.username ?? '',
        }),
      );
    }

    if (type === KeycloakEventType.AuthLogout) {
      this.session.reset();
      this.notificationInbox.disconnect();
    }
  }
}
