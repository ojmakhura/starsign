import { computed, Injectable, signal } from '@angular/core';
import { Env } from '@models/env.model';

/** Holds the runtime configuration from /env.json as a signal. */
@Injectable({ providedIn: 'root' })
export class EnvLoaderService {
  readonly env = signal<Env | null>(null);
  readonly apiUrl = computed(() => this.env()?.apiUrl ?? '');
  /** Keycloak account console for the signed-in user. */
  readonly accountUrl = computed(() => {
    const env = this.env();
    if (!env) {
      return null;
    }
    const params = new URLSearchParams({ referrer: env.clientId, referrer_uri: env.redirectUri });
    return `${env.authDomain}/realms/${env.realm}/account?${params}`;
  });
}
