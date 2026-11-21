import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { NotificationTemplateDTO } from '@models/notification';
import { NotificationTemplateApi } from '@services/notification/notification-template-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type NotificationTemplateApiState = AppState<
  NotificationTemplateDTO,
  NotificationTemplateDTO
> & {
  previewTemplateResult: string | null;
};

const initialState = (): NotificationTemplateApiState => ({
  ...initialAppState<NotificationTemplateDTO, NotificationTemplateDTO>(),
  previewTemplateResult: null,
});

/** State and actions for /notification-templates. Scaffolded by scripts/generate-api.py; safe to edit. */
export const NotificationTemplateApiStore = signalStore(
  { providedIn: 'root' },
  withState<NotificationTemplateApiState>(initialState()),
  withMethods((store, api = inject(NotificationTemplateApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    findTemplateById: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading template by id ...'));
        return api.findTemplateById(id).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Template by id loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findTemplates: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading templates ...'));
        return api.findTemplates(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ dataList: data }, 'Templates loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    previewTemplate: rxMethod<{
      template: NotificationTemplateDTO;
      model: Record<string, unknown>;
    }>(
      concatMap(({ template, model }) => {
        patchState(store, requestStarted('Previewing template ...'));
        return api.previewTemplate(template, model).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ previewTemplateResult: data }, 'Template previewed.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeTemplate: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Deleting template ...'));
        return api.removeTemplate(id).pipe(
          tapResponse({
            next: () =>
              patchState(
                store,
                requestSucceeded(
                  { data: null, dataList: store.dataList().filter((item) => item.id !== id) },
                  'Template deleted.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    saveTemplate: rxMethod<{ template: NotificationTemplateDTO }>(
      concatMap(({ template }) => {
        patchState(store, requestStarted('Saving template ...'));
        return api.saveTemplate(template).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Template saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
