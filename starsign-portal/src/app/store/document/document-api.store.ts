import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { AuditEventDTO } from '@models/audit';
import {
  DocumentDTO,
  DocumentIntegrityReportDTO,
  DocumentListDTO,
  DocumentMetadataDTO,
  DocumentPageDTO,
  DocumentSearchCriteria,
  DocumentUploadDTO,
  DocumentVersionDTO,
} from '@models/document';
import { DocumentApi } from '@services/document/document-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type DocumentApiState = AppState<DocumentDTO, DocumentListDTO> & {
  auditEventList: AuditEventDTO[];
  documentMetadata: DocumentMetadataDTO | null;
  documentPageList: DocumentPageDTO[];
  documentVersionList: DocumentVersionDTO[];
  documentIntegrityReport: DocumentIntegrityReportDTO | null;
};

const initialState = (): DocumentApiState => ({
  ...initialAppState<DocumentDTO, DocumentListDTO>(),
  auditEventList: [],
  documentMetadata: null,
  documentPageList: [],
  documentVersionList: [],
  documentIntegrityReport: null,
});

/** State and actions for /documents. Scaffolded by scripts/generate-api.py; safe to edit. */
export const DocumentApiStore = signalStore(
  { providedIn: 'root' },
  withState<DocumentApiState>(initialState()),
  withMethods((store, api = inject(DocumentApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    archive: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Archiving document ...'));
        return api.archive(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Document archived.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findByDocument: rxMethod<{ documentId: string }>(
      switchMap(({ documentId }) => {
        patchState(store, requestStarted('Loading by document ...'));
        return api.findByDocument(documentId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ auditEventList: data }, 'By document loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findById: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading by id ...'));
        return api.findById(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'By id loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getMetadata: rxMethod<{ id: string; versionNumber?: number }>(
      switchMap(({ id, versionNumber }) => {
        patchState(store, requestStarted('Loading metadata ...'));
        return api.getMetadata(id, versionNumber).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ documentMetadata: data }, 'Metadata loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getPages: rxMethod<{ id: string; versionNumber?: number }>(
      switchMap(({ id, versionNumber }) => {
        patchState(store, requestStarted('Loading pages ...'));
        return api.getPages(id, versionNumber).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ documentPageList: data }, 'Pages loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getVersions: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading versions ...'));
        return api.getVersions(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ documentVersionList: data }, 'Versions loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    remove: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Deleting document ...'));
        return api.remove(id).pipe(
          tapResponse({
            next: () =>
              patchState(
                store,
                requestSucceeded(
                  { data: null, dataList: store.dataList().filter((item) => item.id !== id) },
                  'Document deleted.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<DocumentSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is DocumentSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching documents ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(store, requestSucceeded({ dataList: data }, 'Documents loaded.')),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
    updateDetails: rxMethod<{ id: string; details: DocumentUploadDTO }>(
      concatMap(({ id, details }) => {
        patchState(store, requestStarted('Updating details ...'));
        return api.updateDetails(id, details).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Details updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    upload: rxMethod<{ file: File; details: DocumentUploadDTO }>(
      concatMap(({ file, details }) => {
        patchState(store, requestStarted('Uploading document ...'));
        return api.upload(file, details).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Document uploaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    verifyDocument: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Verifying document ...'));
        return api.verifyDocument(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ documentIntegrityReport: data }, 'Document verified.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    verifyFile: rxMethod<{ file: File }>(
      concatMap(({ file }) => {
        patchState(store, requestStarted('Verifying file ...'));
        return api.verifyFile(file).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ documentIntegrityReport: data }, 'File verified.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
