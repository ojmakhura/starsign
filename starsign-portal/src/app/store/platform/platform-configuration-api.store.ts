import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { SecurityPolicyDTO, SystemConfigurationDTO } from '@models/platform';
import { PlatformConfigurationApi } from '@services/platform/platform-configuration-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type PlatformConfigurationApiState = AppState<SecurityPolicyDTO, SystemConfigurationDTO> & {
  systemConfiguration: SystemConfigurationDTO | null;
};

const initialState = (): PlatformConfigurationApiState => ({
  ...initialAppState<SecurityPolicyDTO, SystemConfigurationDTO>(),
  systemConfiguration: null,
});

/** State and actions for /admin/configuration. Scaffolded by scripts/generate-api.py; safe to edit. */
export const PlatformConfigurationApiStore = signalStore(
  { providedIn: 'root' },
  withState<PlatformConfigurationApiState>(initialState()),
  withMethods((store, api = inject(PlatformConfigurationApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    findAll: rxMethod<{ category: string }>(
      switchMap(({ category }) => {
        patchState(store, requestStarted('Loading all ...'));
        return api.findAll(category).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ dataList: data }, 'All loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getSecurityPolicy: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading security policy ...'));
        return api.getSecurityPolicy().pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Security policy loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    save: rxMethod<{ configuration: SystemConfigurationDTO }>(
      concatMap(({ configuration }) => {
        patchState(store, requestStarted('Saving platform configuration ...'));
        return api.save(configuration).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ systemConfiguration: data }, 'Platform configuration saved.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    updateSecurityPolicy: rxMethod<{ policy: SecurityPolicyDTO }>(
      concatMap(({ policy }) => {
        patchState(store, requestStarted('Updating security policy ...'));
        return api.updateSecurityPolicy(policy).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Security policy updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
