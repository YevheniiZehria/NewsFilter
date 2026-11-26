# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

NewsFilter: native Android news app (Kotlin, Jetpack Compose, Hilt, Room, Retrofit/Moshi, DataStore, WorkManager) backed by n8n webhooks + PostgreSQL. Code comments, KDoc and all user-facing strings are in Romanian — keep that convention. `DOCUMENTATIE.md` is the long-form project documentation (university assignment, requirements in `Cerință.pdf`).

## Commands

There is no `java` on PATH on this machine; point Gradle at the bundled Temurin JDK (Git Bash):

    export JAVA_HOME="/c/Users/eugen/.jdks/temurin-21.0.12.1"
    ./gradlew :app:compileDebugKotlin --console=plain   # fast check (includes KSP: Room, Hilt, Moshi codegen)
    ./gradlew :app:assembleDebug --console=plain        # debug APK

There are no unit/instrumented tests and no lint configuration beyond Android defaults.

Backend (local):

    cd backend && docker compose up -d     # postgres:16 + n8n on :5678, env from backend/.env
    docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < postgres_schema.sql

`postgres_schema.sql` is idempotent and is only auto-run when the Postgres volume is first created — re-run it manually after schema changes. Workflow JSONs in `backend/` must be re-imported into n8n by hand (then re-select the Postgres credential in each Postgres node and activate).

## Configuration

- `local.properties` (git-ignored, see `local.properties.example`) → `BuildConfig` fields in `app/build.gradle.kts`: `N8N_BASE_URL` (default `http://10.0.2.2:5678/webhook/`, must end with `/`), `N8N_API_KEY`, `CLERK_DOMAIN`, `CLERK_CLIENT_ID`.
- `backend/.env` (git-ignored, see `.env.example`) → n8n container env: `N8N_API_KEY` (must equal the app's), `GEMINI_API_KEY`, `CLERK_DOMAIN`, `POSTGRES_*`. `N8N_BLOCK_ENV_ACCESS_IN_NODE=false` is required because workflows read `$env`.

## Architecture

Single module `app`, package `ro.newsfilter.app`, layered `domain` (models + repository interfaces) / `data` (Room, DataStore, Retrofit, repository impls) / `ui` (Compose screens + ViewModels) / `core` (DI, auth, network, audio, sensor, util). Hilt bindings live in `core/di/`.

**Two activities.** `AuthActivity` (launcher; login/register/Google, handles the `newsfilter://oauth-callback` deep link) and `MainActivity` (Compose NavGraph: Onboarding, Feed, Bookmarks, Settings, Detail). Neither navigates on button presses directly: both observe `UserPreferences.isLoggedIn` from DataStore and switch activities when it changes (`AuthViewModel.startupState`, `NewsFilterAppContent` session guard).

**Auth / sessions (strict, server-only — no offline login).**
- n8n issues an opaque 256-bit session token on `/auth/login`, `/users`, `/auth/google`; Postgres `sessions` stores only its SHA-256 hash (30-day expiry, `revoked_at`).
- Google: `ClerkOAuthHelper` runs Clerk OAuth 2.0 + PKCE in Custom Tabs (verifier/state persisted in SharedPreferences to survive process death) and returns only the Clerk access token; n8n verifies it against `https://$CLERK_DOMAIN/oauth/userinfo`. The app never sends a trusted email.
- `SessionManager` is the single owner of the token: encrypts it with an Android Keystore AES-GCM key (`TokenCipher`) into DataStore, caches it in memory. `isLoggedIn` is true only if the encrypted token exists.
- OkHttp chain (`NetworkModule`): `ApiKeyInterceptor` (`X-API-Key` on every call) → `AuthInterceptor` (Bearer token; on 401 from a non-auth endpoint calls `SessionManager.onUnauthorized` → session + Room wiped → UI returns to login).
- `SessionManager.endSession()` also clears the whole Room cache (per-account data); bookmarks are restored from the server via `NewsRepository.syncBookmarks()` on next login.

**News data (offline-first).** Room is the single source of truth for the UI. `FeedViewModel` collects `NewsRepository.observeFeed(categories)` (unread articles, `NewsDao.observeFeed`); `syncFeed()` only writes fetched `/news` results into Room via `upsertKeepingLocalState` (preserves local `is_read`, `user_vote`, `deep_dive`, bookmark state). Sync triggers: screen open, pull-to-refresh, network regained, `NewsSyncWorker` (periodic 3h, scheduled in `NewsFilterApp.onCreate`, plus a one-off ~90s after "Force refresh"). Network errors become user text through `Throwable.toUserMessage()` (`core/network/ApiErrors.kt`). `NewsDto` fields are all nullable on purpose; invalid rows are dropped by `toDomainOrNull()` instead of failing the whole list.

**Room schema** (`AppDatabase`, currently v4) uses explicit migrations registered in `DatabaseModule` plus exported schemas in `app/schemas/` — bump the version, add a `MIGRATION_x_y`, and let KSP export the new JSON. List fields (`ai_bullets`, `ai_tags`) are stored as `\n`-joined strings (no TypeConverters).

## n8n backend

- `workflow_2_api_webhooks.json` — the REST API. Every route: Webhook → API-key IF → (protected routes) session-check Postgres node + IF → work → Respond node. Shared 403/401 responders. Routes: `POST auth/login|auth/google|auth/logout|users|bookmarks|feedback|deep-dive`, `GET news|bookmarks|categories`. List endpoints return a single item `{list}` and respond `JSON.stringify($json.list)` so empty results are still `[]`.
- `workflow_1_ingestion_and_processing.json` — BBC RSS ingestion (schedule 30 min, manual, or `POST refresh` from the app, rate-limited to once per 3 min via `refresh_requests`): normalize to app category slugs (`tech`, `economy`, …) → scraping → Gemini "Smart Digest" (JSON: summary + 2-3 bullets + tags) → upsert `articles`.
- Postgres node parameters are passed as array expressions (`={{ [a, b] }}`), never comma-separated strings (commas in values break mapping). Tokens/hashes are generated in SQL with pgcrypto (`gen_random_bytes`, `digest`), not in Code nodes.
- Category values must match `domain/model/Category.slug` exactly; the Room feed query filters on them.

## Working agreement

The user normally wants a stop-and-ask workflow: before architectural changes, n8n payload changes, new dependencies, DB schema changes or auth-screen rewrites, present 2 options with pros/cons and wait for approval; deliver one fix/feature at a time. Keep code minimal and pragmatic.
