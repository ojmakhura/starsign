import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { NotificationDTO, NotificationSearchCriteria } from '@models/notification';
import { NotificationApi } from '@services/notification/notification-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type NotificationApiState = AppState<NotificationDTO, NotificationDTO>;

const initialState = (): NotificationApiState => ({
  ...initialAppState<NotificationDTO, NotificationDTO>(),
});

/** State and actions for /notifications. Scaffolded by scripts/generate-api.py; safe to edit. */
export const NotificationApiStore = signalStore(
  { providedIn: 'root' },
  withState<NotificationApiState>(initialState()),
  withMethods((store, api = inject(NotificationApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    findMine: rxMethod<{ unreadOnly?: boolean }>(
      switchMap(({ unreadOnly }) => {
        patchState(store, requestStarted('Loading mine ...'));
        return api.findMine(unreadOnly).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ dataList: data }, 'Mine loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    markRead: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Marking read ...'));
        return api.markRead(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Read marked.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<NotificationSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is NotificationSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching notifications ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(store, requestSucceeded({ dataList: data }, 'Notifications loaded.')),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
  })),
);
