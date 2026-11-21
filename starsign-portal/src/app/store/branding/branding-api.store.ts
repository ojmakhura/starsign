import { inject } from '@angular/core';
import { tapResponse } from '@ngrx/operators';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { BrandingDTO, BrandingPreviewDTO, BrandingRequest } from '@models/branding';
import { BrandingApi } from '@services/branding/branding-api';
import { concatMap, switchMap } from 'rxjs';
import {
  AppState,
  initialAppState,
  requestFailed,
  requestStarted,
  requestSucceeded,
} from '../app-state';

export type BrandingApiState = AppState<BrandingDTO, never> & {
  brandingPreview: BrandingPreviewDTO | null;
};

const initialState = (): BrandingApiState => ({
  ...initialAppState<BrandingDTO, never>(),
  brandingPreview: null,
});

/** State and actions for /branding. Scaffolded by scripts/generate-api.py; safe to edit. */
export const BrandingApiStore = signalStore(
  { providedIn: 'root' },
  withState<BrandingApiState>(initialState()),
  withMethods((store, api = inject(BrandingApi)) => ({
    reset(): void {
      patchState(store, initialState());
    },
    getForOrganisation: rxMethod<{ organisationId: string }>(
      switchMap(({ organisationId }) => {
        patchState(store, requestStarted('Loading for organisation ...'));
        return api.getForOrganisation(organisationId).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'For organisation loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    getPlatform: rxMethod<void>(
      switchMap(() => {
        patchState(store, requestStarted('Loading platform ...'));
        return api.getPlatform().pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Platform loaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    preview: rxMethod<{ branding: BrandingRequest }>(
      concatMap(({ branding }) => {
        patchState(store, requestStarted('Previewing branding ...'));
        return api.preview(branding).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ brandingPreview: data }, 'Branding previewed.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removeOrganisationAsset: rxMethod<{ organisationId: string; asset: string }>(
      concatMap(({ organisationId, asset }) => {
        patchState(store, requestStarted('Deleting organisation asset ...'));
        return api.removeOrganisationAsset(organisationId, asset).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Organisation asset deleted.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    removePlatformAsset: rxMethod<{ asset: string }>(
      concatMap(({ asset }) => {
        patchState(store, requestStarted('Deleting platform asset ...'));
        return api.removePlatformAsset(asset).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Platform asset deleted.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    resetForOrganisation: rxMethod<{ organisationId: string }>(
      concatMap(({ organisationId }) => {
        patchState(store, requestStarted('Resetting for organisation ...'));
        return api.resetForOrganisation(organisationId).pipe(
          tapResponse({
            next: () => patchState(store, requestSucceeded({}, 'For organisation reset.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    resetPlatform: rxMethod<void>(
      concatMap(() => {
        patchState(store, requestStarted('Resetting platform ...'));
        return api.resetPlatform().pipe(
          tapResponse({
            next: () => patchState(store, requestSucceeded({}, 'Platform reset.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    saveForOrganisation: rxMethod<{ organisationId: string; branding: BrandingRequest }>(
      concatMap(({ organisationId, branding }) => {
        patchState(store, requestStarted('Saving for organisation ...'));
        return api.saveForOrganisation(organisationId, branding).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'For organisation saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    savePlatform: rxMethod<{ branding: BrandingRequest }>(
      concatMap(({ branding }) => {
        patchState(store, requestStarted('Saving platform ...'));
        return api.savePlatform(branding).pipe(
          tapResponse({
            next: (data) => patchState(store, requestSucceeded({ data }, 'Platform saved.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    uploadOrganisationAsset: rxMethod<{ organisationId: string; asset: string; file: File }>(
      concatMap(({ organisationId, asset, file }) => {
        patchState(store, requestStarted('Uploading organisation asset ...'));
        return api.uploadOrganisationAsset(organisationId, asset, file).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Organisation asset uploaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
    uploadPlatformAsset: rxMethod<{ asset: string; file: File }>(
      concatMap(({ asset, file }) => {
        patchState(store, requestStarted('Uploading platform asset ...'));
        return api.uploadPlatformAsset(asset, file).pipe(
          tapResponse({
            next: (data) =>
              patchState(store, requestSucceeded({ data }, 'Platform asset uploaded.')),
            error: (error) => patchState(store, requestFailed(error)),
          }),
        );
      }),
    ),
  })),
);
