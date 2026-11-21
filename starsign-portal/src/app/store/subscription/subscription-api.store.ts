import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  PlanChangeDTO,
  SubscriptionDTO,
  SubscriptionPlanDTO,
  UsageSummaryDTO,
} from '@models/subscription';
import { SubscriptionApi } from '@services/subscription/subscription-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type SubscriptionApiState = AppState<SubscriptionDTO, SubscriptionPlanDTO> & {
  usageSummary: UsageSummaryDTO | null;
  subscriptionPlan: SubscriptionPlanDTO | null;
};

const initialState = (): SubscriptionApiState => ({
  ...initialAppState<SubscriptionDTO, SubscriptionPlanDTO>(),
  usageSummary: null,
  subscriptionPlan: null,
});

/** State and actions for /subscriptions. Scaffolded by scripts/generate-api.py; safe to edit. */
export const SubscriptionApiStore = signalStore(
  { providedIn: 'root' },
  withState<SubscriptionApiState>(initialState()),
  withMethods((store, api = inject(SubscriptionApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    cancel: rxMethod<{ organisationId: string }>(
      concatMap(({ organisationId }) => {
        patchState(store, requestStarted('Cancelling subscription ...'));
        return api.cancel(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Subscription cancelled.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    changePlan: rxMethod<{ organisationId: string; change: PlanChangeDTO }>(
      concatMap(({ organisationId, change }) => {
        patchState(store, requestStarted('Changing plan ...'));
        return api.changePlan(organisationId, change).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Plan changed.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findPlans: rxMethod<{ includeInactive?: boolean }>(
      switchMap(({ includeInactive }) => {
        patchState(store, requestStarted('Loading plans ...'));
        return api.findPlans(includeInactive).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Plans loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getSubscription: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading subscription ...'));
        return api.getSubscription(organisationId).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Subscription loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getUsageSummary: rxMethod<{ organisationId: string; from: string; to: string }>(
      switchMap(({ organisationId, from, to }) => {
        patchState(store, requestStarted('Loading usage summary ...'));
        return api.getUsageSummary(organisationId, from, to).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ usageSummary: data }, 'Usage summary loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    savePlan: rxMethod<{ plan: SubscriptionPlanDTO }>(
      concatMap(({ plan }) => {
        patchState(store, requestStarted('Saving plan ...'));
        return api.savePlan(plan).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ subscriptionPlan: data }, 'Plan saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
