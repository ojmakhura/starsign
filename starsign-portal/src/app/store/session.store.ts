import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { CurrentUserDTO } from '@models/identity';
import { UserApi } from '@services/identity/user-api';
import { exhaustMap } from 'rxjs';
import { apiErrorMessage } from './app-state';

export interface KeycloakProfile {
  firstName: string;
  lastName: string;
  email: string;
  username: string;
}

type SessionState = {
  authenticated: boolean;
  profile: KeycloakProfile | null;
  currentUser: CurrentUserDTO | null;
  permissionsLoaded: boolean;
  loading: boolean;
  error: string | null;
};

const initialState: SessionState = {
  authenticated: false,
  profile: null,
  currentUser: null,
  permissionsLoaded: false,
  loading: false,
  error: null,
};

/** Normalises "SCOPE_documents:view" to "documents:view". */
const normalise = (permission: string) => permission.replace(/^SCOPE_/, '');

/**
 * The signed-in user: their Keycloak profile, StarSign account, organisation and
 * permission scopes (from GET /users/me). Everything else derives from it as signals.
 */
export const SessionStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed((store) => ({
    permissions: computed(() => (store.currentUser()?.permissions ?? []).map(normalise)),
    organisationId: computed(() => store.currentUser()?.organisation?.id ?? null),
    organisationName: computed(() => store.currentUser()?.organisation?.name || null),
    pendingSignatureCount: computed(() => store.currentUser()?.pendingSignatureCount ?? 0),
    displayName: computed(() => {
      const profile = store.profile();
      const name = [profile?.firstName, profile?.lastName].filter(Boolean).join(' ');
      const user = store.currentUser()?.user;
      const accountName = [user?.firstName, user?.lastName].filter(Boolean).join(' ');
      return name || accountName || profile?.username || 'Signed in';
    }),
    initials: computed(() => {
      const profile = store.profile();
      const initials = `${profile?.firstName?.[0] ?? ''}${profile?.lastName?.[0] ?? ''}`;
      return (initials || profile?.username?.[0] || '?').toUpperCase();
    }),
  })),
  withMethods((store, userApi = inject(UserApi)) => ({
    setAuthenticated(authenticated: boolean): void {
      patchState(store, { authenticated });
    },
    setProfile(profile: KeycloakProfile | null): void {
      patchState(store, { profile });
    },
    setPendingSignatureCount(pendingSignatureCount: number): void {
      const currentUser = store.currentUser();
      if (currentUser) {
        patchState(store, { currentUser: { ...currentUser, pendingSignatureCount } });
      }
    },
    reset(): void {
      patchState(store, initialState);
    },
    loadCurrentUser: rxMethod<void>(
      exhaustMap(() => {
        patchState(store, { loading: true, error: null });
        return userApi.getCurrentUser().pipe(
          tapResponse({
            next: (currentUser) =>
              patchState(store, { currentUser, permissionsLoaded: true, loading: false }),
            // Fail closed: no permissions, but stop waiting so guards can decide.
            error: (error) =>
              patchState(store, {
                currentUser: null,
                permissionsLoaded: true,
                loading: false,
                error: apiErrorMessage(error, 'Unable to load your account.'),
              }),
          }),
        );
      }),
    ),
  })),
);
