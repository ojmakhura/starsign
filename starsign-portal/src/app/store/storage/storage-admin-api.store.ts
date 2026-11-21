import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  ReconciliationResolution,
  ReconciliationRunDTO,
  StorageReconciliationIssueDTO,
  StorageUsageDTO,
} from '@models/storage';
import { StorageAdminApi } from '@services/storage/storage-admin-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type StorageAdminApiState = AppState<StorageUsageDTO, StorageReconciliationIssueDTO> & {
  storageReconciliationIssue: StorageReconciliationIssueDTO | null;
  retryFailedUploadsResult: number | null;
  reconciliationRun: ReconciliationRunDTO | null;
};

const initialState = (): StorageAdminApiState => ({
  ...initialAppState<StorageUsageDTO, StorageReconciliationIssueDTO>(),
  storageReconciliationIssue: null,
  retryFailedUploadsResult: null,
  reconciliationRun: null,
});

/** State and actions for /admin/storage. Scaffolded by scripts/generate-api.py; safe to edit. */
export const StorageAdminApiStore = signalStore(
  { providedIn: 'root' },
  withState<StorageAdminApiState>(initialState()),
  withMethods((store, api = inject(StorageAdminApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    findIssues: rxMethod<{ resolution: ReconciliationResolution }>(
      switchMap(({ resolution }) => {
        patchState(store, requestStarted('Loading issues ...'));
        return api.findIssues(resolution).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Issues loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getUsage: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading usage ...'));
        return api.getUsage(organisationId).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Usage loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    resolveIssue: rxMethod<{ id: string; resolution: ReconciliationResolution }>(
      concatMap(({ id, resolution }) => {
        patchState(store, requestStarted('Resolving issue ...'));
        return api.resolveIssue(id, resolution).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ storageReconciliationIssue: data }, 'Issue resolved.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    retryFailedUploads: rxMethod<void>(
      concatMap(() => {
        patchState(store, requestStarted('Retrying failed uploads ...'));
        return api.retryFailedUploads().pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ retryFailedUploadsResult: data }, 'Failed uploads retried.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    runReconciliation: rxMethod<{ organisationId: string }>(
      concatMap(({ organisationId }) => {
        patchState(store, requestStarted('Running reconciliation ...'));
        return api.runReconciliation(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ reconciliationRun: data }, 'Reconciliation completed.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
