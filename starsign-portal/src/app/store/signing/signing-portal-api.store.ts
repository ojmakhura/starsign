import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  DeclineRequestDTO,
  SignatureSubmissionDTO,
  SigningFieldValueDTO,
  SigningResultDTO,
  SigningSessionDTO,
  VerificationCodeDTO,
  VerificationRequestDTO,
  VerificationResultDTO,
} from '@models/signing';
import { SigningPortalApi } from '@services/signing/signing-portal-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type SigningPortalApiState = AppState<SigningSessionDTO, never> & {
  verificationResult: VerificationResultDTO | null;
  signingResult: SigningResultDTO | null;
  signingFieldValueList: SigningFieldValueDTO[];
};

const initialState = (): SigningPortalApiState => ({
  ...initialAppState<SigningSessionDTO, never>(),
  verificationResult: null,
  signingResult: null,
  signingFieldValueList: [],
});

/** State and actions for /signing/invitations. Scaffolded by scripts/generate-api.py; safe to edit. */
export const SigningPortalApiStore = signalStore(
  { providedIn: 'root' },
  withState<SigningPortalApiState>(initialState()),
  withMethods((store, api = inject(SigningPortalApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    confirmVerification: rxMethod<{ token: string; code: VerificationCodeDTO }>(
      concatMap(({ token, code }) => {
        patchState(store, requestStarted('Confirming verification ...'));
        return api.confirmVerification(token, code).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ verificationResult: data }, 'Verification confirmed.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    decline: rxMethod<{ token: string; request: DeclineRequestDTO }>(
      concatMap(({ token, request }) => {
        patchState(store, requestStarted('Declining to sign ...'));
        return api.decline(token, request).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signingResult: data }, 'Signing request declined.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getSession: rxMethod<{ token: string }>(
      switchMap(({ token }) => {
        patchState(store, requestStarted('Loading signing session ...'));
        return api.getSession(token).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Signing session loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    saveFieldValues: rxMethod<{ token: string; values?: SigningFieldValueDTO[] }>(
      concatMap(({ token, values }) => {
        patchState(store, requestStarted('Saving field values ...'));
        return api.saveFieldValues(token, values).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signingFieldValueList: data }, 'Field values saved.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    sign: rxMethod<{ token: string; submission: SignatureSubmissionDTO }>(
      concatMap(({ token, submission }) => {
        patchState(store, requestStarted('Signing document ...'));
        return api.sign(token, submission).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingResult: data }, 'Document signed.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    startVerification: rxMethod<{ token: string; request: VerificationRequestDTO }>(
      concatMap(({ token, request }) => {
        patchState(store, requestStarted('Starting verification ...'));
        return api.startVerification(token, request).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ verificationResult: data }, 'Verification started.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
