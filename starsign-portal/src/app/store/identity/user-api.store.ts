import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  CurrentUserDTO,
  KeycloakSessionDTO,
  OrganisationUserDTO,
  OrganisationUserListDTO,
  OrganisationUserSearchCriteria,
  RoleAssignmentDTO,
  UserProfileUpdateDTO,
} from '@models/identity';
import { UserApi } from '@services/identity/user-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type UserApiState = AppState<OrganisationUserDTO, OrganisationUserListDTO> & {
  currentUser: CurrentUserDTO | null;
  getPermissionsResult: string[];
  keycloakSessionList: KeycloakSessionDTO[];
};

const initialState = (): UserApiState => ({
  ...initialAppState<OrganisationUserDTO, OrganisationUserListDTO>(),
  currentUser: null,
  getPermissionsResult: [],
  keycloakSessionList: [],
});

/** State and actions for /users. Scaffolded by scripts/generate-api.py; safe to edit. */
export const UserApiStore = signalStore(
  { providedIn: 'root' },
  withState<UserApiState>(initialState()),
  withMethods((store, api = inject(UserApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    activate: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Activating user ...'));
        return api.activate(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'User activated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    assignRoles: rxMethod<{ id: string; assignment: RoleAssignmentDTO }>(
      concatMap(({ id, assignment }) => {
        patchState(store, requestStarted('Assigning roles ...'));
        return api.assignRoles(id, assignment).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Roles assigned.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    deactivate: rxMethod<{ id: string; reason: string }>(
      concatMap(({ id, reason }) => {
        patchState(store, requestStarted('Deactivating user ...'));
        return api.deactivate(id, reason).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'User deactivated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findById: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading by id ...'));
        return api.findById(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'By id loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getCurrentUser: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading your account ...'));
        return api.getCurrentUser().pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ currentUser: data }, 'Account loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getPermissions: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading permissions ...'));
        return api.getPermissions(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ getPermissionsResult: data }, 'Permissions loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getSessions: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading sessions ...'));
        return api.getSessions(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ keycloakSessionList: data }, 'Sessions loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    remove: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Deleting user ...'));
        return api.remove(id).pipe(
          tapResponse({
            next: () =>
              patchState(
                store,
                requestSucceeded(
                  { data: null, dataList: store.dataList().filter((item) => item.id !== id) },
                  'User deleted.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<OrganisationUserSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is OrganisationUserSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching organisation users ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(
                  store,
                  requestSucceeded({ dataList: data }, 'Organisation users loaded.'),
                ),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
    unlock: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Unlocking user ...'));
        return api.unlock(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'User unlocked.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    updateProfile: rxMethod<{ id: string; profile: UserProfileUpdateDTO }>(
      concatMap(({ id, profile }) => {
        patchState(store, requestStarted('Updating profile ...'));
        return api.updateProfile(id, profile).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Profile updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
