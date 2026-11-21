/**
 * Spring ProblemDetail returned by the API on errors (the *ControllerAdvice classes).
 * Hand-written: the generated ApiErrorDTO in ./common is not what the advice sends.
 */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  /** Field-level validation errors, keyed by the request property path. */
  errors?: Record<string, string> | { field: string; message: string }[];
}
