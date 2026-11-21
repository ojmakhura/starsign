import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import {
  OrganisationUserListDTO,
  UserInvitationDTO,
  UserInvitationRequestDTO,
  UserInvitationStatus,
} from '@models/identity';
import {
  OrganisationDTO,
  OrganisationListDTO,
  OrganisationNotificationSettingsDTO,
  OrganisationRegistrationDTO,
  OrganisationSearchCriteria,
  OrganisationSigningPolicyDTO,
  OrganisationStatusChangeDTO,
} from '@models/organisation';
import { OrganisationApi } from '@services/organisation/organisation-api';
import { concatMap, filter, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type OrganisationApiState = AppState<OrganisationDTO, OrganisationListDTO> & {
  userInvitationList: UserInvitationDTO[];
  organisationUserListList: OrganisationUserListDTO[];
  organisationNotificationSettings: OrganisationNotificationSettingsDTO | null;
  organisationSigningPolicy: OrganisationSigningPolicyDTO | null;
  userInvitation: UserInvitationDTO | null;
};

const initialState = (): OrganisationApiState => ({
  ...initialAppState<OrganisationDTO, OrganisationListDTO>(),
  userInvitationList: [],
  organisationUserListList: [],
  organisationNotificationSettings: null,
  organisationSigningPolicy: null,
  userInvitation: null,
});

/** State and actions for /organisations. Scaffolded by scripts/generate-api.py; safe to edit. */
export const OrganisationApiStore = signalStore(
  { providedIn: 'root' },
  withState<OrganisationApiState>(initialState()),
  withMethods((store, api = inject(OrganisationApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    changeStatus: rxMethod<{ id: string; change: OrganisationStatusChangeDTO }>(
      concatMap(({ id, change }) => {
        patchState(store, requestStarted('Changing status ...'));
        return api.changeStatus(id, change).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Status changed.')),
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
    findInvitations: rxMethod<{ organisationId: string; status: UserInvitationStatus }>(
      switchMap(({ organisationId, status }) => {
        patchState(store, requestStarted('Loading invitations ...'));
        return api.findInvitations(organisationId, status).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ userInvitationList: data }, 'Invitations loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    findUsers: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading users ...'));
        return api.findUsers(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ organisationUserListList: data }, 'Users loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getCurrentOrganisation: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading organisation ...'));
        return api.getCurrentOrganisation().pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Organisation loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getNotificationSettings: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading notification settings ...'));
        return api.getNotificationSettings(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded(
                  { organisationNotificationSettings: data },
                  'Notification settings loaded.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getSigningPolicy: rxMethod<{ id: string }>(
      switchMap(({ id }) => {
        patchState(store, requestStarted('Loading signing policy ...'));
        return api.getSigningPolicy(id).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ organisationSigningPolicy: data }, 'Signing policy loaded.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    invite: rxMethod<{ organisationId: string; request: UserInvitationRequestDTO }>(
      concatMap(({ organisationId, request }) => {
        patchState(store, requestStarted('Inviting organisation ...'));
        return api.invite(organisationId, request).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ userInvitation: data }, 'Organisation invited.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    register: rxMethod<{ registration: OrganisationRegistrationDTO }>(
      concatMap(({ registration }) => {
        patchState(store, requestStarted('Registering organisation ...'));
        return api.register(registration).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Organisation registered.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    /** Accepts a signal: re-searches whenever the criteria change (null = wait). */
    search: rxMethod<OrganisationSearchCriteria | null>((criteria$) =>
      criteria$.pipe(
        filter((criteria): criteria is OrganisationSearchCriteria => criteria !== null),
        switchMap((criteria) => {
          patchState(store, requestStarted('Searching organisations ...'));
          return api.search(criteria).pipe(
            tapResponse({
              next: (data) =>
                patchState(store, requestSucceeded({ dataList: data }, 'Organisations loaded.')),
              error: (error) => patchState(store, requestFailed(error)),
            }),
          );
        }),
      ),
    ),
    updateNotificationSettings: rxMethod<{
      id: string;
      settings: OrganisationNotificationSettingsDTO;
    }>(
      concatMap(({ id, settings }) => {
        patchState(store, requestStarted('Updating notification settings ...'));
        return api.updateNotificationSettings(id, settings).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded(
                  { organisationNotificationSettings: data },
                  'Notification settings updated.',
                ),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    updateProfile: rxMethod<{ id: string; organisation: OrganisationDTO }>(
      concatMap(({ id, organisation }) => {
        patchState(store, requestStarted('Updating profile ...'));
        return api.updateProfile(id, organisation).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Profile updated.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    updateSigningPolicy: rxMethod<{ id: string; policy: OrganisationSigningPolicyDTO }>(
      concatMap(({ id, policy }) => {
        patchState(store, requestStarted('Updating signing policy ...'));
        return api.updateSigningPolicy(id, policy).pipe(
          tapResponse({
            next: (data) =>
              patchState(
                store,
                requestSucceeded({ organisationSigningPolicy: data }, 'Signing policy updated.'),
              ),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
