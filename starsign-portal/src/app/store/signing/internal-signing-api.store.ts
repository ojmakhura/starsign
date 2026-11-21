import { computed, inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  CertificateInfoDTO,
  DeclineRequestDTO,
  PendingSignatureDTO,
  SignatureEvidenceDTO,
  SignatureSubmissionDTO,
  SigningResultDTO,
  SigningSessionDTO,
} from '@models/signing';
import { InternalSigningApi } from '@services/signing/internal-signing-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type InternalSigningApiState = AppState<SigningResultDTO, PendingSignatureDTO> & {
  certificateInfoList: CertificateInfoDTO[];
  signatureEvidenceList: SignatureEvidenceDTO[];
  signingSession: SigningSessionDTO | null;
};

const initialState = (): InternalSigningApiState => ({
  ...initialAppState<SigningResultDTO, PendingSignatureDTO>(),
  certificateInfoList: [],
  signatureEvidenceList: [],
  signingSession: null,
});

/**
 * The signed-in user's signing inbox (dataList) and internal signing actions.
 * Scaffolded by scripts/generate-api.py; the computed signals are hand-written.
 */
export const InternalSigningApiStore = signalStore(
  { providedIn: 'root' },
  withState<InternalSigningApiState>(initialState()),
  withComputed((store) => ({
    pendingCount: computed(() => store.dataList().length),
    /** Requests whose deadline is within 48 hours. */
    dueSoon: computed(() => {
      const limit = Date.now() + 48 * 3600_000;
      return store
        .dataList()
        .filter((item) => item.deadline && new Date(item.deadline).getTime() <= limit);
    }),
  })),
  withMethods((store, api = inject(InternalSigningApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    declineAsInternalUser: rxMethod<{
      workflowId: string;
      signatoryId: string;
      request: DeclineRequestDTO;
    }>(
      concatMap(({ workflowId, signatoryId, request }) => {
        patchState(store, requestStarted('Declining to sign ...'));
        return api.declineAsInternalUser(workflowId, signatoryId, request).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Signing request declined.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getCertificates: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading certificates ...'));
        return api.getCertificates(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ certificateInfoList: data }, 'Certificates loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getEvidence: rxMethod<{ workflowId: string }>(
      switchMap(({ workflowId }) => {
        patchState(store, requestStarted('Loading evidence ...'));
        return api.getEvidence(workflowId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signatureEvidenceList: data }, 'Evidence loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getInternalSession: rxMethod<{ workflowId: string; signatoryId: string }>(
      switchMap(({ workflowId, signatoryId }) => {
        patchState(store, requestStarted('Loading signing session ...'));
        return api.getInternalSession(workflowId, signatoryId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signingSession: data }, 'Signing session loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getPendingForCurrentUser: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading your signing inbox ...'));
        return api.getPendingForCurrentUser().pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Signing inbox loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    signAsInternalUser: rxMethod<{
      workflowId: string;
      signatoryId: string;
      submission: SignatureSubmissionDTO;
    }>(
      concatMap(({ workflowId, signatoryId, submission }) => {
        patchState(store, requestStarted('Signing document ...'));
        return api.signAsInternalUser(workflowId, signatoryId, submission).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Document signed.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
