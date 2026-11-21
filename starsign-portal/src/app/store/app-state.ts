import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetail } from '@models/problem-detail';

/** Shape shared by every API signal store (same contract as the cohortix portals). */
export type AppState<T, L> = {
  data: T | null;
  dataList: L[];
  error: boolean;
  loading: boolean;
  success: boolean;
  messages: string[];
  loaderMessage: string;
};

export function initialAppState<T, L>(): AppState<T, L> {
  return {
    data: null,
    dataList: [],
    error: false,
    loading: false,
    success: false,
    messages: [],
    loaderMessage: '',
  };
}

/** Patch applied when a request starts. */
export const requestStarted = (loaderMessage = 'Loading ...') => ({
  loading: true,
  loaderMessage,
  error: false,
  success: false,
  messages: [] as string[],
});

/** Patch applied when a request succeeds. */
export const requestSucceeded = <P extends object>(patch: P, message: string) => ({
  ...patch,
  loading: false,
  success: true,
  error: false,
  messages: [message],
});

/** Patch applied when a request fails. */
export const requestFailed = (error: unknown) => ({
  loading: false,
  success: false,
  error: true,
  messages: [apiErrorMessage(error)],
});

/** The human-readable message of an API error (ProblemDetail detail, then title). */
export function apiErrorMessage(error: unknown, fallback = 'An error occurred'): string {
  if (error instanceof HttpErrorResponse) {
    const problem = error.error as ProblemDetail | null;
    if (error.status === 0) {
      return 'The StarSign service could not be reached.';
    }
    if (error.status === 403) {
      return problem?.detail || 'You do not have permission to do this.';
    }
    return problem?.detail || problem?.title || error.message || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

/** Field-level errors of a ProblemDetail as { propertyPath: message }. */
export function apiFieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof HttpErrorResponse)) {
    return {};
  }
  const errors = (error.error as ProblemDetail | null)?.errors;
  if (!errors) {
    return {};
  }
  return Array.isArray(errors)
    ? Object.fromEntries(errors.map((entry) => [entry.field, entry.message]))
    : errors;
}
