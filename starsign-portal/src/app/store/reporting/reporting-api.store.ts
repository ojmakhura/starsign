import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  DashboardSummaryDTO,
  DocumentVolumeRowDTO,
  PlatformStatisticsDTO,
  ReportCriteria,
  SignatoryActivityRowDTO,
  SigningPerformanceDTO,
  UserActivityRowDTO,
} from '@models/reporting';
import { StorageUsageDTO } from '@models/storage';
import { ReportingApi } from '@services/reporting/reporting-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type ReportingApiState = AppState<DashboardSummaryDTO, DocumentVolumeRowDTO> & {
  platformStatistics: PlatformStatisticsDTO | null;
  signatoryActivityRowList: SignatoryActivityRowDTO[];
  signingPerformance: SigningPerformanceDTO | null;
  storageUsage: StorageUsageDTO | null;
  userActivityRowList: UserActivityRowDTO[];
};

const initialState = (): ReportingApiState => ({
  ...initialAppState<DashboardSummaryDTO, DocumentVolumeRowDTO>(),
  platformStatistics: null,
  signatoryActivityRowList: [],
  signingPerformance: null,
  storageUsage: null,
  userActivityRowList: [],
});

/** State and actions for /reports. Scaffolded by scripts/generate-api.py; safe to edit. */
export const ReportingApiStore = signalStore(
  { providedIn: 'root' },
  withState<ReportingApiState>(initialState()),
  withMethods((store, api = inject(ReportingApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    documentVolume: rxMethod<{ criteria: ReportCriteria }>(
      concatMap(({ criteria }) => {
        patchState(store, requestStarted('Loading document volume ...'));
        return api.documentVolume(criteria).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Document volume loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getDashboard: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading dashboard ...'));
        return api.getDashboard(organisationId).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Dashboard loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    platformStatistics: rxMethod<{ from: string; to: string }>(
      switchMap(({ from, to }) => {
        patchState(store, requestStarted('Loading platform statistics ...'));
        return api.platformStatistics(from, to).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ platformStatistics: data }, 'Platform statistics loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    signatoryActivity: rxMethod<{ criteria: ReportCriteria }>(
      concatMap(({ criteria }) => {
        patchState(store, requestStarted('Loading signatory activity ...'));
        return api.signatoryActivity(criteria).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signatoryActivityRowList: data }, 'Signatory activity loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    signingPerformance: rxMethod<{ criteria: ReportCriteria }>(
      concatMap(({ criteria }) => {
        patchState(store, requestStarted('Loading signing performance ...'));
        return api.signingPerformance(criteria).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signingPerformance: data }, 'Signing performance loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    storageUtilisation: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading storage utilisation ...'));
        return api.storageUtilisation(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ storageUsage: data }, 'Storage utilisation loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    userActivity: rxMethod<{ criteria: ReportCriteria }>(
      concatMap(({ criteria }) => {
        patchState(store, requestStarted('Loading user activity ...'));
        return api.userActivity(criteria).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ userActivityRowList: data }, 'User activity loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
