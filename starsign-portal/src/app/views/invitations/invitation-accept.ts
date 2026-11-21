import { Component, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { UserInvitationApi } from '@services/identity/user-invitation-api';
import { apiErrorMessage } from '@store/app-state';
import { SessionStore } from '@store/session.store';
import { InternalSigningApiStore } from '@store/signing/internal-signing-api.store';
import Keycloak from 'keycloak-js';
import { toast } from 'ngx-sonner';
import { firstValueFrom } from 'rxjs';

/**
 * Target of the invitation email's link (/invitations/accept?token=…). The invitee is
 * signed in (signedInGuard) but has no organisation or roles yet; accepting creates their
 * organisation profile and gives them the invited roles, which a refreshed token carries.
 */
@Component({
  selector: 'app-invitation-accept',
  template: `
    <div class="login-screen">
      <section class="login-card stage flex flex-col gap-4" aria-labelledby="accept-title">
        <div class="flex items-center gap-3">
          <img src="/mark.svg" alt="" class="h-9 w-9" />
          <span class="font-display text-headline-sm font-bold">StarSign</span>
        </div>
        <span class="eyebrow mt-4">Invitation</span>
        <h1 id="accept-title" class="m-0 text-headline-md font-bold">Join your organisation</h1>

        @if (!token()) {
          <div class="alert alert--danger" role="alert">
            <i class="bi bi-exclamation-triangle" aria-hidden="true"></i>
            This link has no invitation token. Open the link from your invitation email again.
          </div>
        } @else {
          <p class="m-0 text-ink-muted">
            You're signed in as <strong class="text-ink">{{ signedInAs }}</strong>. Accepting adds
            this account to the organisation that invited you, with the roles it chose.
          </p>

          @if (error()) {
            <div class="alert alert--danger" role="alert">
              <i class="bi bi-exclamation-triangle" aria-hidden="true"></i>{{ error() }}
            </div>
          }

          <button
            type="button"
            class="btn btn--primary btn--block min-h-11"
            [disabled]="accepting()"
            (click)="accept()"
          >
            @if (accepting()) {
              <span class="spinner" aria-hidden="true"></span> Accepting…
            } @else {
              <i class="bi bi-person-check" aria-hidden="true"></i> Accept invitation
            }
          </button>
        }

        <button type="button" class="btn btn--ghost btn--block" (click)="switchAccount()">
          Not you? Sign in with another account
        </button>
      </section>
    </div>
  `,
})
export class InvitationAccept {
  /** From the query string (?token=…), via withComponentInputBinding. */
  readonly token = input<string>();

  private readonly invitationApi = inject(UserInvitationApi);
  private readonly keycloak = inject(Keycloak);
  private readonly session = inject(SessionStore);
  private readonly signing = inject(InternalSigningApiStore);
  private readonly router = inject(Router);

  protected readonly accepting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly signedInAs =
    (this.keycloak.tokenParsed?.['email'] as string | undefined) ??
    (this.keycloak.tokenParsed?.['preferred_username'] as string | undefined) ??
    'this account';

  protected async accept(): Promise<void> {
    this.accepting.set(true);
    this.error.set(null);
    try {
      const user = await firstValueFrom(this.invitationApi.accept({ token: this.token() }));
      // The roles and tenant claims were added in Keycloak: a new token carries them.
      await this.keycloak.updateToken(-1);
      this.session.reset();
      this.session.setAuthenticated(true);
      this.session.loadCurrentUser();
      this.signing.getPendingForCurrentUser();
      toast.success('Invitation accepted', {
        description: user.firstName ? `Welcome, ${user.firstName}. Your account is ready.` : 'Your account is ready.',
      });
      await this.router.navigateByUrl('/');
    } catch (error) {
      this.error.set(apiErrorMessage(error, 'The invitation could not be accepted.'));
    } finally {
      this.accepting.set(false);
    }
  }

  /** Signs out and back in, returning to this link, so the right account accepts. */
  protected switchAccount(): void {
    this.keycloak.logout({ redirectUri: window.location.href });
  }
}
