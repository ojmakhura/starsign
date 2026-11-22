import { DATE_PIPE_DEFAULT_OPTIONS } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withViewTransitions,
} from '@angular/router';
import { Env } from '@models/env.model';
import { EnvLoaderService } from '@services/env-loader.service';
import {
  AutoRefreshTokenService,
  createInterceptorCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  IncludeBearerTokenCondition,
  includeBearerTokenInterceptor,
  provideKeycloak,
  UserActivityService,
  withAutoRefreshToken,
} from 'keycloak-angular';
import { apiPrefixInterceptor } from './@core/http/api-prefix.interceptor';
import { errorHandlerInterceptor } from './@core/http/error-handler.interceptor';
import { App } from './app';
import { routes } from './app.routes';

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Keycloak (PKCE, public client `starsign-portal`) plus the bearer-token interceptor for the API. */
export const provideKeycloakAndInterceptor = (env: Env) => {
  // A relative apiUrl ("/api") is resolved against this origin before matching.
  const apiBase = new URL(env.apiUrl, window.location.origin).href.replace(/\/$/, '');
  const urlConditions = [
    createInterceptorCondition<IncludeBearerTokenCondition>({
      urlPattern: new RegExp(`^(${escapeRegExp(apiBase)}|${escapeRegExp(env.apiUrl)})(/.*)?$`, 'i'),
      bearerPrefix: 'Bearer',
    }),
  ];

  return [
    provideKeycloak({
      config: {
        url: env.authDomain,
        realm: env.realm,
        clientId: env.clientId,
      },
      // No session-status iframe and no silent check-sso: both depend on third-party
      // cookies, and where those are blocked keycloak-js's cookie check can time out
      // and fail init. check-sso then uses a top-level redirect (prompt=none) instead.
      initOptions: {
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
      },
      features: [
        withAutoRefreshToken({
          // Matches the realm's 30 minute SSO idle timeout.
          sessionTimeout: 1_800_000,
          onInactivityTimeout: 'logout',
        }),
      ],
      providers: [AutoRefreshTokenService, UserActivityService],
    }),
    { provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG, useValue: urlConditions },
  ];
};

export const initialiseApp = async () => {
  const env: Env = await fetch('/env.json').then((res) => res.json());

  const appConfig: ApplicationConfig = {
    providers: [
      provideBrowserGlobalErrorListeners(),
      // Dates show as dd-MM-yyyy wherever a template uses | date without its own format
      { provide: DATE_PIPE_DEFAULT_OPTIONS, useValue: { dateFormat: 'dd-MM-yyyy' } },
      provideAppInitializer(() => inject(EnvLoaderService).env.set(env)),
      provideKeycloakAndInterceptor(env),
      provideRouter(
        routes,
        withComponentInputBinding(),
        withViewTransitions(),
        withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      ),
      provideHttpClient(
        withFetch(),
        withInterceptors([
          apiPrefixInterceptor,
          includeBearerTokenInterceptor,
          errorHandlerInterceptor,
        ]),
      ),
    ],
  };

  await bootstrapApplication(App, appConfig);
};
