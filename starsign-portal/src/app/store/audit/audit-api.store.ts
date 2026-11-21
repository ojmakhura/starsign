import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  AuditChainVerificationDTO,
  AuditEventDTO,
  AuditEventSearchCriteria,
  AuditLogIntegrityRecordDTO,
} from '@models/audit';
import { AuditApi } from '@services/audit/audit-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type AuditApiState = AppState<AuditLogIntegrityRecordDTO, AuditEventDTO> & {
  auditChainVerification: AuditChainVerificationDTO | null;
};

const initialState = (): AuditApiState => ({
  ...initialAppState<AuditLogIntegrityRecordDTO, AuditEventDTO>(),
  auditChainVerification: null,
});

/** State and actions for /audit. Scaffolded by scripts/generate-api.py; safe to edit. */
export const AuditApiStore = signalStore(
  { providedIn: 'root' },
  withState<AuditApiState>(initialState()),
  withMethods((store, api = inject(AuditApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    seal: rxMethod<{ chainKey: string }>(
      concatMap(({ chainKey }) => {
        patchState(store, requestStarted('Sealing audit ...'));
        return api.seal(chainKey).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Audit sealed.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<AuditEventSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is AuditEventSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching audit events ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(store, requestSucceeded({ dataList: data }, 'Audit events loaded.')),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
    verifyChain: rxMethod<{ chainKey: string; fromSequence?: number; toSequence?: number }>(
      switchMap(({ chainKey, fromSequence, toSequence }) => {
        patchState(store, requestStarted('Verifying chain ...'));
        return api.verifyChain(chainKey, fromSequence, toSequence).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ auditChainVerification: data }, 'Chain verified.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
