import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  SigningTemplateDTO,
  SigningTemplateSearchCriteria,
  TemplateInstantiationDTO,
} from '@models/template';
import { SigningWorkflowDTO } from '@models/workflow';
import { TemplateApi } from '@services/template/template-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type TemplateApiState = AppState<SigningTemplateDTO, SigningTemplateDTO> & {
  signingWorkflow: SigningWorkflowDTO | null;
};

const initialState = (): TemplateApiState => ({
  ...initialAppState<SigningTemplateDTO, SigningTemplateDTO>(),
  signingWorkflow: null,
});

/** State and actions for /templates. Scaffolded by scripts/generate-api.py; safe to edit. */
export const TemplateApiStore = signalStore(
  { providedIn: 'root' },
  withState<TemplateApiState>(initialState()),
  withMethods((store, api = inject(TemplateApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    activate: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Activating template ...'));
        return api.activate(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Template activated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    archive: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Archiving template ...'));
        return api.archive(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Template archived.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    create: rxMethod<{ template: SigningTemplateDTO }>(
      concatMap(({ template }) => {
        patchState(store, requestStarted('Creating template ...'));
        return api.create(template).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Template created.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    createFromWorkflow: rxMethod<{ workflowId: string; name: string }>(
      concatMap(({ workflowId, name }) => {
        patchState(store, requestStarted('Creating from workflow ...'));
        return api.createFromWorkflow(workflowId, name).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'From workflow created.')),
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
    instantiate: rxMethod<{ id: string; instantiation: TemplateInstantiationDTO }>(
      concatMap(({ id, instantiation }) => {
        patchState(store, requestStarted('Instantiating template ...'));
        return api.instantiate(id, instantiation).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signingWorkflow: data }, 'Template instantiated.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<SigningTemplateSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is SigningTemplateSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching signing templates ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(
                  store,
                  requestSucceeded({ dataList: data }, 'Signing templates loaded.'),
                ),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
    update: rxMethod<{ id: string; template: SigningTemplateDTO }>(
      concatMap(({ id, template }) => {
        patchState(store, requestStarted('Updating template ...'));
        return api.update(id, template).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Template updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
