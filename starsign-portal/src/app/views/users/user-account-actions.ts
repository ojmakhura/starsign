import { HttpClient } from '@angular/common/http';
import { inject, Service } from '@angular/core';
import { UserApi } from '@services/identity/user-api';
import { Observable } from 'rxjs';

/**
 * The UserController endpoints that return no body (requestPasswordReset, requireMfa,
 * terminateSessions). The generated UserApi leaves out void endpoints, so they are
 * called here with the same paths (see keycloak/ROLE_MATRIX.md, all `users:manage`).
 * Replace with UserApi methods once the generator emits them.
 */
@Service()
export class UserAccountActions {
  private readonly http = inject(HttpClient);
  private readonly userApi = inject(UserApi);

  /** POST /users/{id}/password-reset: Keycloak emails a link to choose a new password. */
  requestPasswordReset(id: string): Observable<void> {
    return this.http.post<void>(`${this.userApi.findByIdUrl(id)}/password-reset`, {});
  }

  /** POST /users/{id}/require-mfa: the user sets up an authenticator app at next sign-in. */
  requireMfa(id: string): Observable<void> {
    return this.http.post<void>(`${this.userApi.findByIdUrl(id)}/require-mfa`, {});
  }

  /** DELETE /users/{id}/sessions: signs the user out everywhere. */
  terminateSessions(id: string): Observable<void> {
    return this.http.delete<void>(this.userApi.getSessionsUrl(id));
  }
}
