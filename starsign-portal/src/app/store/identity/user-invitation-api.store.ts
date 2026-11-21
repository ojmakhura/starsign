import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { InvitationAcceptanceDTO, OrganisationUserDTO, UserInvitationDTO } from '@models/identity';
import { UserInvitationApi } from '@services/identity/user-invitation-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type UserInvitationApiState = AppState<UserInvitationDTO, never> & {
  organisationUser: OrganisationUserDTO | null;
};

const initialState = (): UserInvitationApiState => ({
  ...initialAppState<UserInvitationDTO, never>(),
  organisationUser: null,
});

/** State and actions for /invitations. Scaffolded by scripts/generate-api.py; safe to edit. */
export const UserInvitationApiStore = signalStore(
  { providedIn: 'root' },
  withState<UserInvitationApiState>(initialState()),
  withMethods((store, api = inject(UserInvitationApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    accept: rxMethod<{ acceptance: InvitationAcceptanceDTO }>(
      concatMap(({ acceptance }) => {
        patchState(store, requestStarted('Accepting user invitation ...'));
        return api.accept(acceptance).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ organisationUser: data }, 'User invitation accepted.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    resend: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Resending user invitation ...'));
        return api.resend(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'User invitation resent.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    revoke: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Revoking user invitation ...'));
        return api.revoke(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'User invitation revoked.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
