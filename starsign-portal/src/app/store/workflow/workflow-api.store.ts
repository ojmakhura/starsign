import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { AuditEventDTO, AuditEvidenceExportDTO } from '@models/audit';
import { SigningReminderDTO } from '@models/notification';
import {
  CreateWorkflowDTO,
  ReorderRequestDTO,
  SignatoryDTO,
  SigningFieldDTO,
  SigningStageDTO,
  SigningWorkflowDTO,
  SigningWorkflowListDTO,
  SigningWorkflowSearchCriteria,
  WorkflowAmendmentDTO,
  WorkflowCancellationDTO,
  WorkflowConfigurationChangeDTO,
  WorkflowInitiationDTO,
  WorkflowRenewalDTO,
  WorkflowTimelineEntryDTO,
  WorkflowValidationResultDTO,
} from '@models/workflow';
import { WorkflowApi } from '@services/workflow/workflow-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type WorkflowApiState = AppState<SigningWorkflowDTO, SigningWorkflowListDTO> & {
  signatory: SignatoryDTO | null;
  signingStage: SigningStageDTO | null;
  auditEventList: AuditEventDTO[];
  workflowConfigurationChangeList: WorkflowConfigurationChangeDTO[];
  auditEvidenceExport: AuditEvidenceExportDTO | null;
  signingFieldList: SigningFieldDTO[];
  workflowTimelineEntryList: WorkflowTimelineEntryDTO[];
  signatoryList: SignatoryDTO[];
  signingStageList: SigningStageDTO[];
  workflowValidationResult: WorkflowValidationResultDTO | null;
  signingReminder: SigningReminderDTO | null;
};

const initialState = (): WorkflowApiState => ({
  ...initialAppState<SigningWorkflowDTO, SigningWorkflowListDTO>(),
  signatory: null,
  signingStage: null,
  auditEventList: [],
  workflowConfigurationChangeList: [],
  auditEvidenceExport: null,
  signingFieldList: [],
  workflowTimelineEntryList: [],
  signatoryList: [],
  signingStageList: [],
  workflowValidationResult: null,
  signingReminder: null,
});

/** State and actions for /workflows. Scaffolded by scripts/generate-api.py; safe to edit. */
export const WorkflowApiStore = signalStore(
  { providedIn: 'root' },
  withState<WorkflowApiState>(initialState()),
  withMethods((store, api = inject(WorkflowApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    addSignatory: rxMethod<{ id: string; signatory: SignatoryDTO }>(
      concatMap(({ id, signatory }) => {
        patchState(store, requestStarted('Adding signatory ...'));
        return api.addSignatory(id, signatory).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signatory: data }, 'Signatory added.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    addStage: rxMethod<{ id: string; stage: SigningStageDTO }>(
      concatMap(({ id, stage }) => {
        patchState(store, requestStarted('Adding stage ...'));
        return api.addStage(id, stage).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingStage: data }, 'Stage added.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    amend: rxMethod<{ id: string; amendment: WorkflowAmendmentDTO }>(
      concatMap(({ id, amendment }) => {
        patchState(store, requestStarted('Amending workflow ...'));
        return api.amend(id, amendment).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Workflow amended.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    cancel: rxMethod<{ id: string; cancellation: WorkflowCancellationDTO }>(
      concatMap(({ id, cancellation }) => {
        patchState(store, requestStarted('Cancelling workflow ...'));
        return api.cancel(id, cancellation).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Workflow cancelled.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    create: rxMethod<{ workflow: CreateWorkflowDTO }>(
      concatMap(({ workflow }) => {
        patchState(store, requestStarted('Creating workflow ...'));
        return api.create(workflow).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Workflow created.')),
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
    findByWorkflow: rxMethod<{ workflowId: string }>(
      switchMap(({ workflowId }) => {
        patchState(store, requestStarted('Loading by workflow ...'));
        return api.findByWorkflow(workflowId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ auditEventList: data }, 'By workflow loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getConfigurationHistory: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading configuration history ...'));
        return api.getConfigurationHistory(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded(
                  { workflowConfigurationChangeList: data },
                  'Configuration history loaded.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getEvidenceExport: rxMethod<{ workflowId: string }>(
      switchMap(({ workflowId }) => {
        patchState(store, requestStarted('Loading evidence export ...'));
        return api.getEvidenceExport(workflowId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ auditEvidenceExport: data }, 'Evidence export loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getFields: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading fields ...'));
        return api.getFields(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingFieldList: data }, 'Fields loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getTimeline: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading timeline ...'));
        return api.getTimeline(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ workflowTimelineEntryList: data }, 'Timeline loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    initiate: rxMethod<{ id: string; initiation: WorkflowInitiationDTO }>(
      concatMap(({ id, initiation }) => {
        patchState(store, requestStarted('Sending for signing ...'));
        return api.initiate(id, initiation).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Workflow sent for signing.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeField: rxMethod<{ id: string; fieldId: string }>(
      concatMap(({ id, fieldId }) => {
        patchState(store, requestStarted('Deleting field ...'));
        return api.removeField(id, fieldId).pipe(
          tapResponse({
            next: () => patchState(store, requestSucceeded({}, 'Field deleted.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeSignatory: rxMethod<{ id: string; signatoryId: string }>(
      concatMap(({ id, signatoryId }) => {
        patchState(store, requestStarted('Deleting signatory ...'));
        return api.removeSignatory(id, signatoryId).pipe(
          tapResponse({
            next: () => patchState(store, requestSucceeded({}, 'Signatory deleted.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeStage: rxMethod<{ id: string; stageId: string }>(
      concatMap(({ id, stageId }) => {
        patchState(store, requestStarted('Deleting stage ...'));
        return api.removeStage(id, stageId).pipe(
          tapResponse({
            next: () => patchState(store, requestSucceeded({}, 'Stage deleted.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    renew: rxMethod<{ id: string; renewal: WorkflowRenewalDTO }>(
      concatMap(({ id, renewal }) => {
        patchState(store, requestStarted('Renewing workflow ...'));
        return api.renew(id, renewal).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Workflow renewed.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    reorderSignatories: rxMethod<{ id: string; order: ReorderRequestDTO }>(
      concatMap(({ id, order }) => {
        patchState(store, requestStarted('Reordering signatories ...'));
        return api.reorderSignatories(id, order).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ signatoryList: data }, 'Signatories reordered.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    reorderStages: rxMethod<{ id: string; order: ReorderRequestDTO }>(
      concatMap(({ id, order }) => {
        patchState(store, requestStarted('Reordering stages ...'));
        return api.reorderStages(id, order).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingStageList: data }, 'Stages reordered.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    requestEvidenceExport: rxMethod<{ workflowId: string }>(
      concatMap(({ workflowId }) => {
        patchState(store, requestStarted('Requesting evidence export ...'));
        return api.requestEvidenceExport(workflowId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ auditEvidenceExport: data }, 'Evidence export requested.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    saveFields: rxMethod<{ id: string; fields?: SigningFieldDTO[] }>(
      concatMap(({ id, fields }) => {
        patchState(store, requestStarted('Saving fields ...'));
        return api.saveFields(id, fields).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingFieldList: data }, 'Fields saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<SigningWorkflowSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is SigningWorkflowSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching signing workflows ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(
                  store,
                  requestSucceeded({ dataList: data }, 'Signing workflows loaded.'),
                ),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
    sendReminder: rxMethod<{ id: string; signatoryId: string }>(
      concatMap(({ id, signatoryId }) => {
        patchState(store, requestStarted('Sending reminder ...'));
        return api.sendReminder(id, signatoryId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingReminder: data }, 'Reminder sent.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    update: rxMethod<{ id: string; workflow: SigningWorkflowDTO }>(
      concatMap(({ id, workflow }) => {
        patchState(store, requestStarted('Updating workflow ...'));
        return api.update(id, workflow).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Workflow updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    updateSignatory: rxMethod<{ id: string; signatoryId: string; signatory: SignatoryDTO }>(
      concatMap(({ id, signatoryId, signatory }) => {
        patchState(store, requestStarted('Updating signatory ...'));
        return api.updateSignatory(id, signatoryId, signatory).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signatory: data }, 'Signatory updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    updateStage: rxMethod<{ id: string; stageId: string; stage: SigningStageDTO }>(
      concatMap(({ id, stageId, stage }) => {
        patchState(store, requestStarted('Updating stage ...'));
        return api.updateStage(id, stageId, stage).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ signingStage: data }, 'Stage updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    validate: rxMethod<{ id: string }>(
      concatMap(({ id }) => {
        patchState(store, requestStarted('Validating workflow ...'));
        return api.validate(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ workflowValidationResult: data }, 'Workflow validated.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
