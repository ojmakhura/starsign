import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { environment } from '@env/environment';
import Keycloak from 'keycloak-js';
import { catchError, throwError } from 'rxjs';

/** Logs API errors in development and sends the user to sign in again on a 401. */
export const errorHandlerInterceptor: HttpInterceptorFn = (request, next) => {
  const keycloak = inject(Keycloak);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (!environment.production) {
        console.error('[API]', request.method, request.urlWithParams, error);
      }
      if (error instanceof HttpErrorResponse && error.status === 401 && keycloak.authenticated) {
        keycloak.login({ redirectUri: window.location.href });
      }
      return throwError(() => error);
    }),
  );
};
