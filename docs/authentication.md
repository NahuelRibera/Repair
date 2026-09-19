# Authentication (Google OAuth2/OIDC, Google only)

Repair has exactly one sign-in method: Google. There is no email/password
login and never has been — see the productization pass instructions this
was built against.

## Architecture decision: backend-owned OAuth2, not a frontend auth library

Spring Security's own OAuth2 Client support runs the whole login inside
`apps/api`, rather than a separate frontend auth library (NextAuth/Auth.js)
sitting in `apps/web`. Reasons:

- The backend is already the sole ownership authority for every piece of
  data this app cares about (Garage vehicles, maintenance events,
  preferences, conversations) — see `docs/repair-v2-architecture.md`. A
  second, independent auth system in the frontend would mean two sources
  of truth about who's signed in, which is exactly the kind of split that
  causes subtle authorization bugs.
- `apps/web/next.config.ts` already proxies `/api/:path*` to the Java API
  as a single-origin browser experience (`API_ORIGIN`, default
  `http://localhost:8082`, proxied behind `http://localhost:3000` in
  dev). Keeping every OAuth2 endpoint under `/api/**` means that existing
  proxy just works for the entire login dance with zero new proxy rules.
- Cookies are host-only per RFC 6265, not port-specific. In local dev the
  browser only ever talks to `localhost:3000` (Next's dev server); Next's
  rewrite proxies the actual request to `localhost:8082` server-to-server
  and relays the response (including `Set-Cookie`) back untouched — the
  exact same mechanism this project already relied on for the anonymous
  `repair_visitor` cookie since Phase 1. No special cross-origin cookie
  handling was needed for auth either.

## What's stored, and the identity key

`app_users` (Flyway `V8__app_users_and_ownership.sql`, `helmet_avatar_key`
later dropped by `V9__remove_helmet_avatar.sql` — see "Avatar" below):

```
app_users
  id, google_sub (unique, NOT NULL), email, display_name,
  google_picture_url, created_at, updated_at
```

**`google_sub` — the OIDC `sub` claim — is the only identity key, never
email.** Email can change or be reused across Google accounts; `sub` is
Google's own stable, guaranteed-unique per-account identifier. Every
lookup (`AppUserRepository.findByGoogleSub`) uses it.

## Ownership migration

Before this pass, the motorcycle domain (Garage vehicles, conversations)
was owned by an anonymous `visitor_id` cookie (the same pattern the
preserved car prototype still uses — untouched, see `VisitorCookieInterceptor`).
`V8__app_users_and_ownership.sql`:

- Adds a nullable `user_id BIGINT REFERENCES app_users(id)` to
  `garage_vehicles` and `moto_chat_sessions`.
- Makes `visitor_id` on both nullable (not dropped) — old anonymous rows
  from development/QA are preserved on disk, simply no longer reachable
  through the now-authenticated API.
- `maintenance_events`, `vehicle_preferences`, `moto_rag_runs`, and
  `moto_retrieved_evidence` need no `user_id` of their own — ownership
  already flows transitively through `garage_vehicle_id` / `session_id`,
  both of which are now user-owned.

**Manual cleanup of old anonymous rows** (never automatic): once you're
sure no anonymous demo data is worth keeping,

```sql
delete from moto_chat_sessions where visitor_id is not null and user_id is null;
delete from garage_vehicles where visitor_id is not null and user_id is null;
```

Run this by hand, against a real backup, when you decide to — there is no
code path that does it for you.

### V9 — removing the helmet avatar column

`V9__remove_helmet_avatar.sql` drops `app_users.helmet_avatar_key`. The
custom helmet-avatar feature (a closed set of 10 selectable illustrated
avatars) was removed as a product decision after reviewing the real
running app — see "Avatar" below. `V8` was already applied in every
environment this ships to by the time that decision was made, so it was
never edited; this is a forward migration, not a rewrite of history. A
fresh database applies `V8` then `V9` and ends up in the same place as an
existing one that migrates forward.

## Request flow

1. **`GET /api/auth/login?next=/some/path`** (`LoginRedirectController`) —
   the frontend links here as a real browser navigation (never a fetch).
   `next` is validated (`RedirectValidator` — same-origin relative path
   only, rejects `//host` tricks, embedded schemes, and CR/LF header
   injection) and stashed in a short-lived (`10 min`), `HttpOnly`,
   `SameSite=Lax` cookie (`repair_post_login_redirect`) — a cookie rather
   than Spring Security's own OAuth2 `state` parameter, since that `state`
   is already used internally for CSRF protection on the OAuth2 flow
   itself. The response redirects to `/api/oauth2/authorization/google`.
2. Spring Security's OAuth2 Client redirects to Google, with PKCE, `state`,
   and `nonce` all provided automatically by the default OAuth2 login
   client — no extra configuration needed.
3. Google redirects back to **`/api/login/oauth2/code/google`**.
4. **`OAuth2LoginSuccessHandler`** runs exactly once for this login: calls
   `AppUserService.findOrCreate(sub, email, name, picture)` (creates the
   `app_users` row on first login; reuses and refreshes profile fields on
   every subsequent login), reads and clears the pending-redirect cookie,
   re-validates it, and redirects the browser to the frontend (default
   `/chat` if missing/invalid) — see "Why the post-login redirect must be
   absolute" below for exactly how.
5. Every later request: **`AuthenticatedUserInterceptor`** resolves the
   signed-in `OidcUser` from Spring Security's session (one indexed
   lookup by `google_sub`, no network call — the token exchange already
   happened once, at login) into request-scoped `AuthenticatedUserContext`.
6. An unauthenticated request to a protected endpoint gets a plain
   `401 {"error":"unauthenticated"}` from **`ApiAuthenticationEntryPoint`**
   — never a redirect to a login *page*, since every caller here is a
   fetch from the SPA, not a browser page load.
7. **`POST /api/auth/logout`** (Spring Security's default logout, CSRF-
   protected like any other mutation) invalidates the session and clears
   `JSESSIONID`.

## Auth-gated routes

`SecurityConfig` requires `.authenticated()` for:

- `/api/garage/**`, `/api/moto-sessions/**`, `/api/moto-rag-runs/**`
- `/api/me`, `/api/me/**`

Everything else is `permitAll()` — the dynamic motorcycle catalog
(`/api/motorcycles/**`, needed by the public bike picker before sign-in),
the preserved anonymous-cookie car prototype, and the login/logout
endpoints themselves.

On the frontend, `RequireAuth` (`apps/web/src/components/RequireAuth.tsx`)
wraps the `/garage` and `/chat` route layouts: it calls `GET /api/me` (via
the shared `AuthProvider` context so every surface shares one call) and,
if unauthenticated, does a full browser navigation to
`/api/auth/login?next=<current path>` — so the rider lands back exactly
where they tried to go. The landing page (`/`) itself, and its bike
picker, stay public.

### Landing page bike selection before login

A visitor can pick Manufacturer → Model → Year on the public landing page
without signing in (`BikePicker`'s own catalog lookups are public). Only
the next step — creating a Garage vehicle and opening a conversation —
needs auth. `LandingPicker` handles this: if the create-vehicle call comes
back `401`, it stashes `{modelId, year}` in `localStorage`
(`apps/web/src/lib/pendingBikeSelection.ts` — a cookie can't carry this,
since `LoginRedirectController`'s pending-redirect cookie only holds a
path) and navigates to `/api/auth/login?next=/chat`. Once signed in and
landed on `/chat`, `NewChatPage` picks the pending selection back up once,
automatically reuses-or-creates the matching Garage vehicle, and opens the
conversation — the rider never has to re-pick the bike they already chose.

## Profile menu and helmet avatars

Deliberately not a full profile page — **My Garage is the account area**.
The top-right menu (`UserMenu.tsx`) is just: My Garage / Change helmet /
Sign out.

Helmet avatars are a closed, backend-validated set of 10 keys
(`dev.repair.api.auth.HelmetAvatarCatalog.KEYS`: `helmet-01`…`helmet-10`).
Only the key is ever stored — never a URL or file path — so the real
image files can be dropped into
`apps/web/public/avatars/helmets/<key>.png` later with **zero code
change**. Until those files exist, `HelmetAvatar.tsx` falls back to a
plain initial-in-a-circle; see
`apps/web/public/avatars/helmets/ASSETS.md` for the exact filename
contract. `PATCH /api/me/avatar` re-validates the key server-side against
`HelmetAvatarCatalog` regardless of what the client sends.

## Security review

- **CSRF**: `CookieCsrfTokenRepository.withHttpOnlyFalse()` (the standard
  Angular/Spring "double-submit" pattern for a pure-API backend) plus a
  small `OncePerRequestFilter` that forces the token to actually resolve
  on every request (this backend has no server-rendered page that would
  otherwise trigger that resolution). The frontend (`apps/web/src/lib/api.ts`)
  reads the `XSRF-TOKEN` cookie and echoes it as `X-XSRF-TOKEN` on every
  non-GET request.
- **State/nonce/PKCE**: all provided automatically by Spring Security's
  default OAuth2 login client — live-verified end to end (the real
  authorization URL sent to Google carries `code_challenge`,
  `code_challenge_method=S256`, and `state`).
- **Open redirect**: `RedirectValidator` is the single choke point for the
  post-login `next` value, applied both where it originates
  (`LoginRedirectController`) and where it's actually redirected to
  (`OAuth2LoginSuccessHandler`) — same-origin relative path only, no
  `//host`, no embedded scheme, no CR/LF.
- **Safe redirect target for unauthenticated requests**: `ApiAuthenticationEntryPoint`
  returns 401 JSON, never a redirect — there is no login-page redirect for
  an XHR/fetch to be tricked into following.
- **Cookies**: the session cookie is `HttpOnly` with `SameSite=Lax`
  (survives the top-level GET redirect back from Google); `secure` is left
  for the real deployment's HTTPS termination to set (forcing it here
  would break plain `http://localhost` in local dev). The pending-redirect
  cookie is `HttpOnly`, `SameSite=Lax`, and expires in 10 minutes.
- **Token validation**: entirely Spring Security's own OIDC client — no
  hand-rolled JWT parsing anywhere in this codebase.
- **Secrets**: `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` are read only from
  the server-side environment (`.env`, never committed — see
  `.env.example`), the same convention already used for `OPENAI_API_KEY`.
  Spring Boot's OAuth2 client autoconfiguration crashes the *entire* app
  at boot if these resolve to an empty string, so the property fallback is
  a non-empty placeholder (`not-configured`) rather than `""` — the API
  still starts fully, and every public/catalogue/test path still works,
  without real credentials; only actually completing a Google sign-in
  needs the real values (Google itself will reject the placeholder with an
  ordinary OAuth error, not a crash).
- **User-controlled values**: the only rider-controlled account field is
  the helmet key, and it's re-validated server-side against a closed list
  on every write (`MeController.updateAvatar`) — never trusted from the
  client, never a file path.
- **Ownership queries**: every motorcycle-domain repository method takes
  the authenticated `userId` and bakes it into the SQL `WHERE` clause
  itself (see `GarageVehicleRepository`, `MaintenanceRepository`,
  `VehiclePreferenceRepository`, `MotoChatSessionRepository`,
  `MotoRagRunRepository`) — there is no method that reads or writes by id
  alone, and cross-user isolation is covered by
  `GarageVehicleRepositoryIT`.
- Deliberately **not** implemented, as instructed ("don't over-engineer
  enterprise auth"): refresh-token rotation, MFA, account linking across
  multiple Google accounts, rate limiting on the login endpoint, and a
  full profile/settings page. None of these are gaps in *this* app's
  actual threat model — a portfolio demo with one OAuth provider, backend-
  enforced ownership, and no stored secrets client-side.

## Testing without a Google account

There is no live Google account in most environments this runs in, so:

- **`RedirectValidatorTest`**, **`HelmetAvatarCatalogTest`**,
  **`AppUserServiceTest`** — plain unit tests, no OAuth involved.
- **`SecurityConfigIT`** — a real Spring Security filter chain
  (Testcontainers Postgres), asserting the actual authorization rules:
  protected endpoints 401 unauthenticated, the catalog stays public, the
  CSRF cookie is always issued, a mutation without the CSRF header is
  rejected, and `/api/auth/login` really does redirect toward
  `/api/oauth2/authorization/google`.
- **`GarageVehicleRepositoryIT`** — cross-user isolation with real
  `app_users` rows (`seedUser()`), proving one user can never read or
  write another's Garage vehicle, maintenance history, or preferences.
- **`DevLoginController`** (`apps/api/src/main/java/dev/repair/api/auth/DevLoginController.java`)
  — a manual-QA-only substitute for the real Google login, active **only**
  when the Spring profile `dev` is active (never by a property that could
  leak into a real deployment — the bean simply does not exist otherwise):

  ```bash
  SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
  ```

  Then, from the browser (so the CSRF cookie is already present):

  ```js
  fetch("/api/auth/dev-login", {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "X-XSRF-TOKEN": document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1],
    },
    body: JSON.stringify({ sub: "qa-1", email: "qa@example.test", displayName: "QA Rider" }),
  });
  ```

  This builds a real `OidcUser` and stores it in the actual Spring
  Security session the same way a genuine login would (via
  `HttpSessionSecurityContextRepository`), then runs it through the exact
  same `AppUserService.findOrCreate` as a real login — every downstream
  check (ownership, `ApiAuthenticationEntryPoint`,
  `AuthenticatedUserInterceptor`) behaves identically to a real Google
  sign-in. It never weakens or bypasses an authorization check, and it is
  never enabled by `application.properties`, `docker-compose.yml`, or
  anything else that ships by default.

## Setting up real Google OAuth (Google Cloud Console)

1. https://console.cloud.google.com/apis/credentials → **Create
   credentials → OAuth client ID → Web application**.
2. **Authorized JavaScript origins**: `http://localhost:3000` (the origin
   the browser actually navigates to in local dev — Next's dev server).
3. **Authorized redirect URIs**: `http://localhost:8082/api/login/oauth2/code/google`
   — this is the Java API's own port, not the Next.js origin. Spring
   Security's `{baseUrl}` placeholder resolves to whatever origin the
   OAuth2 request actually arrived on; in local dev that's the Java API's
   port because Google redirects the browser straight back to the exact
   `redirect_uri` it was given, and that URI is generated relative to the
   backend, not the Next.js proxy in front of it. (This exact chain —
   `/api/auth/login` → `/api/oauth2/authorization/google` → Google →
   `/api/login/oauth2/code/google` — was live-verified end to end against
   the API directly during this pass.)
4. Copy the generated **Client ID** and **Client secret** into `.env`:
   ```
   GOOGLE_CLIENT_ID=...
   GOOGLE_CLIENT_SECRET=...
   ```
5. Scopes requested: `openid email profile` — the minimum needed to
   identify the rider and show a name/email; nothing else is requested.

For a real (non-localhost) deployment, add that deployment's own origin
and `https://<your-domain>/api/login/oauth2/code/google` redirect URI
alongside (or instead of) the localhost ones above, and set
`server.servlet.session.cookie.secure=true` behind HTTPS.
