import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { PermissionDTO, RoleDTO } from '@models/identity';
import { RoleApi } from '@services/identity/role-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type RoleApiState = AppState<RoleDTO, RoleDTO> & {
  permissionList: PermissionDTO[];
};

const initialState = (): RoleApiState => ({
  ...initialAppState<RoleDTO, RoleDTO>(),
  permissionList: [],
});

/** State and actions for /roles. Scaffolded by scripts/generate-api.py; safe to edit. */
export const RoleApiStore = signalStore(
  { providedIn: 'root' },
  withState<RoleApiState>(initialState()),
  withMethods((store, api = inject(RoleApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    findRoleById: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading role by id ...'));
        return api.findRoleById(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Role by id loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findRoles: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading roles ...'));
        return api.findRoles(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Roles loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getPermissionCatalogue: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading permission catalogue ...'));
        return api.getPermissionCatalogue().pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ permissionList: data }, 'Permission catalogue loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeRole: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Deleting role ...'));
        return api.removeRole(id).pipe(
          tapResponse({
            next: () =>
              patchState(
                store,
                requestSucceeded(
                  { data: null, dataList: store.dataList().filter((item) => item.id !== id) },
                  'Role deleted.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    saveRole: rxMethod<{ role: RoleDTO }>(
      concatMap(({ role }) => {
        patchState(store, requestStarted('Saving role ...'));
        return api.saveRole(role).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Role saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    setRolePermissions: rxMethod<{ id: string; permissionCodes?: string[] }>(
      concatMap(({ id, permissionCodes }) => {
        patchState(store, requestStarted('Setting role permissions ...'));
        return api.setRolePermissions(id, permissionCodes).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Role permissions set.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
