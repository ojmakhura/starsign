import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { NotificationProviderConfigDTO } from '@models/notification';
import { NotificationProviderApi } from '@services/notification/notification-provider-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type NotificationProviderApiState = AppState<
  NotificationProviderConfigDTO,
  NotificationProviderConfigDTO
>;

const initialState = (): NotificationProviderApiState => ({
  ...initialAppState<NotificationProviderConfigDTO, NotificationProviderConfigDTO>(),
});

/** State and actions for /admin/notification-providers. Scaffolded by scripts/generate-api.py; safe to edit. */
export const NotificationProviderApiStore = signalStore(
  { providedIn: 'root' },
  withState<NotificationProviderApiState>(initialState()),
  withMethods((store, api = inject(NotificationProviderApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    findProviders: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading providers ...'));
        return api.findProviders().pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Providers loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeProvider: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Deleting provider ...'));
        return api.removeProvider(id).pipe(
          tapResponse({
            next: () =>
              patchState(
                store,
                requestSucceeded(
                  { data: null, dataList: store.dataList().filter((item) => item.id !== id) },
                  'Provider deleted.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    saveProvider: rxMethod<{ config: NotificationProviderConfigDTO }>(
      concatMap(({ config }) => {
        patchState(store, requestStarted('Saving provider ...'));
        return api.saveProvider(config).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Provider saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    testProvider: rxMethod<{ id: string; recipientAddress: string }>(
      concatMap(({ id, recipientAddress }) => {
        patchState(store, requestStarted('Testing provider ...'));
        return api.testProvider(id, recipientAddress).pipe(
          tapResponse({
            next: () => patchState(store, requestSucceeded({}, 'Provider tested.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
