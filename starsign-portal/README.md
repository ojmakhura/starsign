# StarSign Portal

Angular 22 signing portal for organisation users (Keycloak client `starsign-portal`), styled
with the **StarSign Precision Trust** design system and built around **signals** and
**signal forms**.

## Run

```bash
npm install
npm start            # http://localhost:4700, /api proxied to http://localhost:8085
npm test             # vitest
npm run build
```

Runtime settings live in `public/env.json` (loaded before bootstrap; `env_template.json`
is the deploy-time template):

| Key | Meaning |
|---|---|
| `apiUrl` | API base (`/api` behind the dev proxy) |
| `authDomain`, `realm`, `clientId` | Keycloak (`http://localhost:8080`, `starsign`, `starsign-portal`) |
| `redirectUri` | Where Keycloak returns after login / logout |
| `portalRole` | Client role required to use the portal (`PORTAL_USER`); blank disables the check |

## Security

- `provideKeycloak` with `check-sso`, PKCE S256, silent SSO and auto-refresh (30 min idle logout).
- `authGuard` (keycloak-angular `createAuthGuard`) on every route: signed-out visitors on `/`
  see the sign-in card, deep links go straight to Keycloak; users without `PORTAL_USER` are logged out.
- `permissionGuard` + `*appCan` use the scopes from `GET /users/me` (see `keycloak/ROLE_MATRIX.md`);
  `<scope>-own` grants satisfy the plain scope.
- Bearer tokens are only attached to requests for `apiUrl`.

## Signals architecture

| Concern | Pattern |
|---|---|
| Shared API state | `@ngrx/signals` `signalStore` per API (`store/**`), `AppState` contract as in cohortix |
| Reactive search | `rxMethod` fed a `computed` criteria signal derived from a filter form — no manual subscriptions |
| Detail reads | `httpResource` keyed on route `input()` signals |
| Mutations from a page | `StoreRequest` + `injectLocalStore` (private store instance per page) |
| Session | `SessionStore` — Keycloak profile, `/users/me`, permissions as computed signals |
| Global loader | `LoaderState.track(signal)` |

## Signal forms

| Form | Shows |
|---|---|
| Sign document | `hidden` + conditional `required({ when })`, cross-field `disabled`, custom `FormValueControl` (`SignaturePad`), `applyEach` over dynamic fields with signal-driven `pattern`/`maxLength`, `submission.action` |
| New workflow | reusable `schema()` (`workflow-draft.ts`), nested signatory array, `validateTree` duplicate-email errors targeted per row, multi-request submission with progress |
| Upload | `FileDrop` custom control for `File`, server field errors mapped back onto fields |
| Search filters | `debounce`, shared `dateRange` rule, reset via `form().reset()` |
| Cancel / decline dialogs | small forms with `FormRoot` submit |

## Styling

`src/styles/tailwind.css` holds every DESIGN.md token in a Tailwind `@theme` (utilities such as
`bg-obsidian`, `text-cyan`, `border-hairline-cyan`, `text-headline-sm`, `font-mono`).
`src/styles.scss` + `src/styles/_*.scss` are the SCSS component layer (buttons, fields, chips,
surfaces, timeline) built from mixins over the same CSS variables. Gold is reserved for
seals/verification, cyan for active signing paths, crimson for voided states.
