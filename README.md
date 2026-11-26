# NewsFilter

Aplicație Android care îți livrează un **pachet de știri pe măsura timpului pe care îl ai** (2, 5 sau 15 minute), filtrat după domeniile preferate. Backend: **n8n + PostgreSQL**.

Kotlin · Jetpack Compose · Material 3 · Clean Architecture + MVVM · Hilt · Room · Retrofit · DataStore · WorkManager · Clerk OAuth (PKCE) · Android Keystore

Documentația completă: [DOCUMENTATIE.md](DOCUMENTATIE.md).

## Funcționalități

- **Două Activity-uri:** `AuthActivity` (autentificare, LAUNCHER) → `MainActivity` (Onboarding, Feed, Salvate, Setări, Detaliu).
- **Autentificare strictă:** email + parolă (bcrypt în PostgreSQL), cont nou, **Continuă cu Google** prin Clerk OAuth 2.0 + PKCE (token-ul este verificat de n8n la Clerk). Serverul emite un **token de sesiune per utilizator** (în DB doar hash-ul SHA-256), păstrat pe telefon criptat cu **Android Keystore** și trimis ca `Authorization: Bearer`. Un 401 (sesiune revocată / expirată / cont șters) deconectează automat. Fără server nu există acces.
- **Feed offline-first:** lista vine din cache-ul Room (deschidere instantanee, funcționează offline); sincronizarea cu n8n rulează în fundal (la deschidere, pull-to-refresh, la revenirea rețelei și periodic cu WorkManager).
- **Smart Digest:** n8n + Gemini generează la ingestie 2-3 idei cheie (TL;DR) și etichete, afișate înaintea articolului.
- **Acțiuni app → n8n:** „Forțează actualizarea surselor” (rulează ingestia acum, limitat la o dată la 3 minute), favorite sincronizate per cont (`POST/GET /bookmarks`), **„Aprofundează subiectul”** (analiză AI generată la cerere, păstrată în cache).
- **Evenimente multiple:** tap pe card → detaliu; **apăsare lungă** → vibrație + meniu contextual (Salvează, Marchează ca citit, Distribuie, Deschide în browser).
- **Meniu „⋮”:** reîmprospătare, resetarea istoricului de citire, deconectare.
- **Senzor:** „Scutură pentru știri noi” (accelerometru, cu debouncing), comutator în Setări.
- **Notificare zilnică** programată cu WorkManager la ora aleasă (`TimePickerDialog`).

## Maparea cerințelor din barem

| Cerință | Pct. | Implementare |
|---|---|---|
| ≥ 2 Activity-uri | 10 | [`AuthActivity`](app/src/main/java/ro/newsfilter/app/AuthActivity.kt), [`MainActivity`](app/src/main/java/ro/newsfilter/app/MainActivity.kt), [manifest](app/src/main/AndroidManifest.xml) |
| Autentificare + validare | 10 | [`ui/screens/auth/`](app/src/main/java/ro/newsfilter/app/ui/screens/auth/), [`AuthRepositoryImpl`](app/src/main/java/ro/newsfilter/app/data/repository/AuthRepositoryImpl.kt), endpoint-urile `POST /auth/login` și `POST /users` |
| Click, long-click, notificări | 10 | [`NewsCard`](app/src/main/java/ro/newsfilter/app/ui/components/NewsCard.kt) (`combinedClickable`), [`ArticleActionsSheet`](app/src/main/java/ro/newsfilter/app/ui/components/ArticleActionsSheet.kt), [`worker/`](app/src/main/java/ro/newsfilter/app/worker/) |
| Meniu funcțional | 10 | `FeedOverflowMenu` în [`FeedScreen`](app/src/main/java/ro/newsfilter/app/ui/screens/feed/FeedScreen.kt) |
| Stocare locală + cloud | 20 | Room + DataStore ([`data/local`](app/src/main/java/ro/newsfilter/app/data/local/), [`data/preferences`](app/src/main/java/ro/newsfilter/app/data/preferences/)) + PostgreSQL prin n8n ([`backend/`](backend/)) |
| Cod curat / modularitate | 20 | straturi `domain / data / ui / core`, Hilt, interfețe de repository, KDoc |
| Bonus: Google Sign-In | 5 | [`ClerkOAuthHelper`](app/src/main/java/ro/newsfilter/app/core/auth/ClerkOAuthHelper.kt) + `POST /auth/google` (verificare la Clerk în n8n) |
| Bonus: senzor | 5 | [`ShakeDetector`](app/src/main/java/ro/newsfilter/app/core/sensor/ShakeDetector.kt) |

## Rulare

**Backend**

```bash
cp backend/.env.example backend/.env   # N8N_API_KEY, POSTGRES_*, GEMINI_API_KEY, CLERK_DOMAIN
cd backend && docker compose up -d
```

Variabilele de mediu ale containerului n8n (din `backend/.env`, vezi `docker-compose.yml`):

| Variabilă | Rol |
|---|---|
| `N8N_API_KEY` | cheia verificată în header-ul `X-API-Key` (identică cu cea din `local.properties`) |
| `N8N_BLOCK_ENV_ACCESS_IN_NODE=false` | permite workflow-urilor să citească `$env` (setat în compose) |
| `GEMINI_API_KEY` | Smart Digest + „Aprofundează subiectul” (opțională) |
| `CLERK_DOMAIN` | domeniul Frontend API Clerk; n8n verifică token-urile Google la `https://<domeniu>/oauth/userinfo` |

Importă `workflow_1_ingestion_and_processing.json` și `workflow_2_api_webhooks.json` în n8n (înlocuind versiunile vechi), alege credențialele Postgres în nodurile Postgres și activează ambele workflow-uri.

**Actualizare bază de date existentă** — `postgres_schema.sql` este idempotent (rulează automat doar la crearea volumului):

```bash
cd backend && docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < postgres_schema.sql
```

**Clerk:** o aplicație OAuth *publică* (PKCE) cu redirect URI `newsfilter://oauth-callback`, scope-uri `openid profile email`, și Google activat ca conexiune socială.

**Aplicația**

```bash
cp local.properties.example local.properties   # N8N_BASE_URL, N8N_API_KEY, CLERK_DOMAIN, CLERK_CLIENT_ID
./gradlew assembleDebug
```

**Scuturarea pe emulator:** *Extended controls → Virtual sensors*, sau `adb emu sensor set acceleration 30:0:9.8` repetat de câteva ori.
