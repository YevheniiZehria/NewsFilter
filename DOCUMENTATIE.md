# NewsFilter — Documentația proiectului

> Aplicație Android care îți livrează un **„pachet de știri” pe măsura timpului pe care îl ai** (2, 5 sau 15 minute), filtrat după categoriile preferate. Backend-ul este construit în **n8n** (workflow-uri low-code) peste o bază de date **PostgreSQL**.

---

## Cuprins

1. [Pe scurt](#1-pe-scurt)
2. [Structura directoarelor](#2-structura-directoarelor)
3. [Stack tehnologic](#3-stack-tehnologic)
4. [Arhitectura generală](#4-arhitectura-generală)
5. [Backend (n8n + PostgreSQL)](#5-backend-n8n--postgresql)
6. [Contractul API](#6-contractul-api)
7. [Aplicația Android — straturi](#7-aplicația-android--straturi)
8. [Ecrane și navigare](#8-ecrane-și-navigare)
9. [Fluxuri principale](#9-fluxuri-principale)
10. [Configurare și rulare](#10-configurare-și-rulare)
11. [Probleme identificate și recomandări](#11-probleme-identificate-și-recomandări)
12. [Maparea cerințelor din barem](#12-maparea-cerințelor-din-barem)

---

## 1. Pe scurt

| Element | Valoare |
|---|---|
| Nume aplicație | **NewsFilter** |
| Pachet / applicationId | `ro.newsfilter.app` |
| Versiune | `1.0.0` (versionCode 1) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Limbaj | Kotlin 2.0.21, JVM 17 |
| UI | Jetpack Compose + Material 3 |
| Arhitectură | Clean Architecture (data / domain / ui) + MVVM |
| Backend | n8n (2 workflow-uri) + PostgreSQL |
| Limba interfeței | Română |
| Activity-uri | 2 — `AuthActivity` (LAUNCHER) și `MainActivity` |
| Autentificare | email + parolă (n8n + PostgreSQL, fallback local) și Google (Credential Manager) |

**Ideea centrală:** utilizatorul alege câte minute are la dispoziție. Backend-ul selectează, cu un algoritm *greedy*, cele mai recente articole din categoriile preferate a căror durată de citire însumată încape în bugetul de timp. Articolele deja citite sunt excluse.

---

## 2. Structura directoarelor

```
ProiectAndroid/
├── build.gradle.kts              # build top-level (plugin-uri, apply false)
├── settings.gradle.kts           # rootProject = "NewsFilter", include(":app")
├── gradle.properties
├── local.properties              # sdk.dir + N8N_BASE_URL/N8N_API_KEY/GOOGLE_WEB_CLIENT_ID (local)
├── local.properties.example      # Șablon pentru local.properties
├── gradle/
│   ├── libs.versions.toml        # Version Catalog — toate dependențele
│   └── wrapper/gradle-wrapper.properties   # Gradle 8.10.2
├── backend/
│   ├── postgres_schema.sql                     # schema DB (articles, article_feedback, users) + seed
│   ├── workflow_1_ingestion_and_processing.json # n8n: RSS → Postgres
│   └── workflow_2_api_webhooks.json             # n8n: API REST pt. aplicație
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/ (values, xml, drawable, mipmap)
        └── java/ro/newsfilter/app/
            ├── AuthActivity.kt          # Activity 1 (LAUNCHER): autentificare
            ├── MainActivity.kt          # Activity 2: experiența principală
            ├── NewsFilterApp.kt
            ├── core/
            │   ├── auth/        GoogleSignInHelper (Credential Manager)
            │   ├── di/          NetworkModule, DatabaseModule, RepositoryModule
            │   ├── network/     ApiKeyInterceptor
            │   ├── sensor/      ShakeDetector + ShakeToRefreshEffect (accelerometru)
            │   └── util/        NetworkMonitor, ArticleIntents
            ├── data/
            │   ├── local/       AppDatabase, dao/NewsDao, entity/NewsEntity
            │   ├── remote/      NewsApiService, dto/(NewsDto, CategoriesResponseDto,
            │   │                FeedbackRequestDto, AuthDtos, Mappers)
            │   ├── preferences/ UserPreferencesRepository (DataStore: preferințe + sesiune)
            │   └── repository/  NewsRepositoryImpl, AuthRepositoryImpl
            ├── domain/
            │   ├── model/       NewsArticle, Category, User (+ AuthProvider, AuthResult)
            │   └── repository/  NewsRepository, AuthRepository (interfețe)
            ├── worker/          DailyNewsNotificationWorker, NotificationScheduler
            └── ui/
                ├── NewsFilterAppContent.kt
                ├── navigation/  Routes, NavGraph, BottomNavBar
                ├── screens/     auth, onboarding, feed, detail, bookmarks, settings
                ├── components/  NewsCard, ArticleActionsSheet, CategoryChip, TimeChipSelector,
                │                OfflineBanner, ErrorView, LoadingShimmer, ScreenHeader
                └── theme/       Color, Theme, Type
```

---

## 3. Stack tehnologic

Toate versiunile sunt centralizate în `gradle/libs.versions.toml`.

| Domeniu | Bibliotecă | Versiune | Rol |
|---|---|---|---|
| Build | Android Gradle Plugin | 8.7.2 | |
| Build | Gradle | 8.10.2 | |
| Build | KSP | 2.0.21-1.0.28 | procesare adnotări (Hilt, Room, Moshi) |
| UI | Compose BOM | 2024.10.01 | Compose + Material 3 + icons extended |
| UI | Navigation Compose | 2.8.4 | navigare între ecrane |
| UI | Coil | 2.7.0 | încărcare imagini async cu cache |
| UI | AndroidX Browser | 1.8.0 | Chrome Custom Tabs pt. articolul sursă |
| DI | Hilt | 2.52 | injecție de dependențe (+ hilt-work, hilt-navigation-compose) |
| Rețea | Retrofit | 2.11.0 | client HTTP declarativ |
| Rețea | OkHttp logging | 4.12.0 | logare cereri (doar debug) |
| Rețea | Moshi | 1.15.1 | JSON (codegen KSP, fără reflecție) |
| Persistență | Room | 2.6.1 | cache offline + favorite + citite |
| Persistență | DataStore Preferences | 1.1.1 | preferințele utilizatorului |
| Background | WorkManager | 2.10.0 | notificarea zilnică |
| Async | Coroutines | 1.9.0 | Flow / suspend |
| Autentificare | Credential Manager (`androidx.credentials`) | 1.3.0 | „Continuă cu Google” |
| Autentificare | Google ID (`googleid`) | 1.1.1 | `GetSignInWithGoogleOption`, `GoogleIdTokenCredential` |

---

## 4. Arhitectura generală

```
 ┌────────────────────┐   la 30 min    ┌──────────────────────────┐
 │  RSS BBC Tech      │ ─────────────▶ │ n8n Workflow 1           │
 │  RSS BBC Business  │                │ Ingestie & normalizare   │
 └────────────────────┘                └────────────┬─────────────┘
                                                    │ INSERT / UPSERT
                                                    ▼
                                       ┌──────────────────────────┐
                                       │ PostgreSQL               │
                                       │ articles, feedback, users│
                                       └────────────┬─────────────┘
                                                    │ SELECT / INSERT
                                                    ▼
                                       ┌──────────────────────────┐
                                       │ n8n Workflow 2 (API)     │
                                       │ GET /news  GET /categories│
                                       │ POST /feedback /auth/login│
                                       │ POST /users               │
                                       └────────────┬─────────────┘
                                                    │ HTTPS + X-API-Key
                                                    ▼
 ┌───────────────────────────────────────────────────────────────────┐
 │ Aplicația Android                                                 │
 │  UI (Compose) → ViewModel → NewsRepository → Retrofit / Room      │
 │                                      DataStore (preferințe)       │
 │                                      WorkManager (notificare)     │
 └───────────────────────────────────────────────────────────────────┘
```

Pe dispozitiv, arhitectura respectă Clean Architecture:

- **domain** — modele pure (`NewsArticle`, `Category`, `User`) și contractele `NewsRepository` / `AuthRepository`; fără dependențe de framework.
- **data** — implementarea repository-ului, Retrofit (remote), Room (local), DataStore (preferințe), mapperi DTO ↔ Domain ↔ Entity.
- **ui** — ecrane Compose + ViewModel-uri Hilt care expun `StateFlow<UiState>`.
- **core** — module Hilt, interceptor, utilitare.

---

## 5. Backend (n8n + PostgreSQL)

### 5.1 Schema PostgreSQL — `backend/postgres_schema.sql`

**Tabela `articles`**

| Coloană | Tip | Note |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` (extensia `pgcrypto`) |
| `title` | VARCHAR(500) | NOT NULL |
| `source` | VARCHAR(100) | NOT NULL |
| `image_url` | TEXT | opțional |
| `summary` | TEXT | NOT NULL |
| `article_url` | TEXT | NOT NULL, **UNIQUE** (cheia de deduplicare) |
| `read_time_minutes` | INT | implicit 2 |
| `category` | VARCHAR(50) | slug (`tech`, `sport`, …) |
| `published_at` | TIMESTAMPTZ | implicit `NOW()` |
| `created_at` | TIMESTAMPTZ | implicit `NOW()` |

Indecși: `category`, `published_at DESC`, `read_time_minutes`.

**Tabela `article_feedback`**

| Coloană | Tip | Note |
|---|---|---|
| `id` | SERIAL PK | |
| `article_id` | UUID FK → `articles(id)` | `ON DELETE CASCADE` |
| `is_positive` | BOOLEAN | like / dislike |
| `created_at` | TIMESTAMPTZ | |

**Tabela `users`** (autentificare din aplicație)

| Coloană | Tip | Note |
|---|---|---|
| `id` | UUID PK | `gen_random_uuid()` |
| `email` | VARCHAR(255) | NOT NULL, **UNIQUE**, salvat cu litere mici |
| `name` | VARCHAR(120) | numele afișat |
| `password_hash` | TEXT | bcrypt (`crypt(parola, gen_salt('bf'))`); NULL pentru conturile Google |
| `auth_provider` | VARCHAR(20) | `email` sau `google` (CHECK) |
| `created_at` / `last_login_at` | TIMESTAMPTZ | |

**Date seed:** 5 articole demo în română (tech, sport, economy, science, local), cu `ON CONFLICT DO NOTHING`.

### 5.2 Workflow 1 — *Ingestion & Processing*

```
Schedule Trigger (30 min) ─┬─▶ RSS BBC Technology ─┐
                           └─▶ RSS BBC Business  ──┴─▶ Code: Normalizare & Calcul Timp ─▶ Postgres INSERT
```

Nodul **Normalizare & Calcul Timp** (JavaScript):
- ignoră item-urile fără titlu sau link;
- **timp de citire** = `max(1, round(nr_cuvinte / 200))` (~200 cuvinte/minut, calculat pe snippet);
- imaginea vine din `enclosure.url` sau `media:thumbnail`, altfel o imagine Unsplash implicită;
- **categoria**: `economy` dacă link-ul conține „business”, altfel `tech`;
- elimină tag-urile HTML din rezumat; `source` = „BBC News”.

Inserarea face **UPSERT** pe `article_url` (actualizează `summary` și `read_time_minutes`).

### 5.3 Workflow 2 — *API Webhooks*

| Endpoint | Pași |
|---|---|
| `GET /news` | Webhook → IF verificare `X-API-Key` → SELECT ultimele 50 articole → Code „Pachet de Timp (Greedy)” → răspuns JSON array |
| `GET /categories` | Webhook → răspuns static cu 9 categorii |
| `POST /feedback` | Webhook → INSERT în `article_feedback` → `{ "status": "ok" }` |
| `POST /auth/login` | Webhook → IF `X-API-Key` (altfel 403) → `UPDATE users … WHERE password_hash = crypt($2, password_hash) RETURNING …` → Code: 200 + profil / 401 |
| `POST /users` | Webhook → IF `X-API-Key` (altfel 403) → `INSERT … ON CONFLICT (email) DO UPDATE … WHERE provider = google RETURNING …` → Code: 200 + profil / 409 |

**Algoritmul greedy (`GET /news`):**
1. citește `maxMinutes` (implicit 5), `categories` (CSV) și `exclude` (CSV de ID-uri);
2. filtrează după categorie și exclude articolele citite;
3. *fallback*: dacă nu rămâne nimic, ia toate articolele necitite indiferent de categorie;
4. parcurge articolele (ordonate după `published_at DESC`) și le adaugă cât timp `read_time ≤ timp_rămas`;
5. dacă pachetul e gol, returnează măcar primul articol.

---

## 6. Contractul API

Baza URL vine din `BuildConfig.N8N_BASE_URL`. Toate cererile primesc automat headerele `X-API-Key` și `Accept: application/json` (prin `ApiKeyInterceptor`).

### `GET /news?categories=tech,sport&maxMinutes=5&exclude=id1,id2`

```json
[
  {
    "id": "uuid",
    "title": "…",
    "source": "BBC News",
    "image_url": "https://…",
    "summary": "…",
    "article_url": "https://…",
    "read_time_minutes": 2,
    "category": "tech",
    "published_at": "2026-11-17T08:00:00Z"
  }
]
```

### `GET /categories`

```json
{ "categories": ["tech","sport","economy","politics","science","local","health","entertainment","world"] }
```

### `POST /feedback`

```json
{ "article_id": "uuid", "is_positive": true }
```

### `POST /auth/login`

```json
{ "email": "ana@exemplu.ro", "password": "parola123" }
```
`200` → `{ "id": "uuid", "email": "…", "name": "Ana", "auth_provider": "email" }` · `401` credențiale greșite · `403` cheie API invalidă.

### `POST /users`

```json
{ "email": "ana@exemplu.ro", "name": "Ana Popescu", "password": "parola123", "auth_provider": "email" }
```
Pentru Google: `"password": ""`, `"auth_provider": "google"` (profilul se creează sau se actualizează).
`200` → profilul · `409` există deja un cont cu acest email.

---

## 7. Aplicația Android — straturi

### 7.1 Punct de intrare

- **`NewsFilterApp`** — `@HiltAndroidApp`; implementează `Configuration.Provider` ca WorkManager să folosească `HiltWorkerFactory`; creează canalul de notificări `daily_news_channel`. Inițializatorul implicit WorkManager este eliminat din manifest.
- **`AuthActivity`** (LAUNCHER) — verifică sesiunea din DataStore; dacă există, deschide imediat `MainActivity`. Altfel afișează `AuthScreen` (login / cont nou / Google). Fluxul Credential Manager e pornit de aici (are nevoie de Activity). La succes: `startActivity(MainActivity)` cu tranziție fade + `finish()`.
- **`MainActivity`** (neexportată) — găzduiește navigarea Compose (`NewsFilterAppContent`). Gardă de sesiune: când `isLoggedIn` devine `false` (Deconectare), revine la `AuthActivity` cu `FLAG_ACTIVITY_CLEAR_TASK`.

### 7.2 `core`

| Fișier | Rol |
|---|---|
| `di/NetworkModule` | Moshi, OkHttp (timeout 30 s, interceptor cheie, logging BODY doar în debug), Retrofit, `NewsApiService` |
| `di/DatabaseModule` | Room `newsfilter_db` cu `fallbackToDestructiveMigration()`, `NewsDao` |
| `di/RepositoryModule` | `@Binds NewsRepositoryImpl → NewsRepository`, `AuthRepositoryImpl → AuthRepository` |
| `auth/GoogleSignInHelper` | „Sign in with Google” prin Credential Manager; întoarce `Success` / `Cancelled` / `Unavailable` (fără excepții) |
| `sensor/ShakeDetector` | accelerometru: forța g > 2,2 de 2 ori în 600 ms → scuturare; cooldown 2 s (debouncing); `ShakeToRefreshEffect` îl leagă de ciclul de viață (activ doar în RESUMED) |
| `network/ApiKeyInterceptor` | adaugă `X-API-Key` din `BuildConfig` |
| `util/NetworkMonitor` | `Flow<Boolean>` de conectivitate (`callbackFlow` + `NetworkCallback`, cerință INTERNET + VALIDATED) |

### 7.3 `domain`

- **`NewsArticle`** — model pur cu `isRead`, `isBookmarked` și proprietăți derivate `categoryLabel`, `categoryEmoji`.
- **`Category`** (enum) — 9 categorii cu slug, etichetă în română și emoji:
  `tech 💻, sport ⚽, economy 📈, politics 🏛️, science 🔬, local 📍, health 🏥, entertainment 🎬, world 🌍`.
- **`NewsRepository`** — contract: `getFilteredNews`, `getCategories`, `sendFeedback`, `markAsRead`, `toggleBookmark`, `getBookmarkedArticles`, `getReadArticleIds`, `getArticleById`, `clearOldCache`.

### 7.4 `data`

**Room — tabela `news` (`NewsEntity`)**: aceleași câmpuri ca API-ul + flag-uri locale `is_read`, `is_bookmarked` și `cached_at`. O singură tabelă servește drept cache, favorite și istoric de citire.

**`NewsDao`**: `insertAll` / `insert` (REPLACE), `getNewsByCategories` (Flow), `getBookmarkedArticles` (Flow), `getArticleById`, `markAsRead`, `toggleBookmark` (`NOT is_bookmarked`), `getReadArticleIds`, `clearOldCache` (păstrează favoritele), `clearAllNonBookmarked`.

**`UserPreferencesRepository`** (DataStore `user_preferences`):

| Cheie | Implicit |
|---|---|
| `selected_categories` (CSV) | gol |
| `default_minutes` | 5 |
| `is_dark_theme` | false |
| `onboarding_completed` | false |
| `daily_notification_enabled` | false |
| `notification_hour` / `notification_minute` | 08:00 |
| `shake_to_refresh` | true |
| `is_logged_in`, `user_email`, `user_name`, `auth_provider` | sesiunea curentă |
| `local_account_<email>` | hash SHA-256 al parolei + nume (fallback offline) |

**`AuthRepositoryImpl`** — *cloud first, local fallback*: cererea merge la n8n (timeout 8 s). Răspunsurile 401 / 409 ale serverului sunt decisive; dacă serverul nu răspunde (offline, 404, 5xx, timeout), se folosește contul local din DataStore, astfel încât autentificarea merge și fără backend. La succes scrie sesiunea în DataStore.

**`NewsRepositoryImpl`** — strategie *network-first cu fallback pe cache*:
1. apelează `GET /news`;
2. pentru fiecare articol primit păstrează flag-urile locale existente (`isRead`, `isBookmarked`) și îl salvează în Room;
3. la eroare de rețea citește din Room, filtrează și reconstruiește local pachetul cu același algoritm greedy (`buildTimePack`);
4. `sendFeedback` și `getCategories` înghit excepțiile.

**Mappers** (`dto/Mappers.kt`): `NewsDto → NewsArticle → NewsEntity` și `NewsEntity → NewsArticle`.

### 7.5 `worker`

- **`NotificationScheduler`** — programează un `PeriodicWorkRequest` la 24 h, cu întârziere inițială calculată până la ora aleasă (unic, politica `UPDATE`); are și metodă de anulare.
- **`DailyNewsNotificationWorker`** (`@HiltWorker`) — citește preferințele, cere pachetul de știri (categorii implicite `tech, economy, sport` dacă nu sunt alese) și afișează o notificare `BigTextStyle` cu primele 3 titluri; la eroare → `Result.retry()`.

---

## 8. Ecrane și navigare

Rute (`Routes.kt`): `onboarding`, `feed`, `bookmarks`, `settings`, `detail/{articleId}`.
Bara de jos (`BottomNavBar`) apare doar pe **Pentru tine / Salvate / Setări**. Ecranul de start este Onboarding dacă nu a fost finalizat, altfel Feed.

| Ecran | ViewModel | Ce face |
|---|---|---|
| **Onboarding** | `OnboardingViewModel` | selecție multiplă de categorii (minim una) + timp implicit 2/5/15 min; salvează în DataStore |
| **Feed – „Pentru tine”** | `FeedViewModel` | salut personalizat, meniu „⋮” (reîmprospătare, resetare istoric, deconectare), selector de timp, sumar, listă de carduri cu tap (detaliu) + apăsare lungă (meniu contextual), pull-to-refresh, scuturare |
| **Detaliu** | `DetailViewModel` | citește articolul din Room, îl marchează automat ca citit; imagine, rezumat, buton „deschide articolul” (Custom Tab), like/dislike → `POST /feedback`, bookmark |
| **Salvate** | `BookmarksViewModel` | listă reactivă din Room a favoritelor; eliminare bookmark |
| **Setări** | `SettingsViewModel` | cont (nume, email, metodă) + Deconectare cu confirmare, temă întunecată, timp implicit, categorii, „Scutură pentru știri noi”, notificare zilnică + `TimePickerDialog` |
| **Autentificare** (`AuthActivity`) | `AuthViewModel` | comutator Autentificare / Cont nou, validare în timp real, afișare/ascundere parolă, „Continuă cu Google” + selector demonstrativ |

**Componente reutilizabile:** `HeroNewsCard` / `CompactNewsCard` (tap + apăsare lungă), `ArticleActionsSheet` (meniu contextual), `CategoryChip` (+ `getCategoryColor`), `TimeChipSelector` (2/5/15), `OfflineBanner`, `ErrorView`, `LoadingShimmer`.

**Temă:** paletă editorială Material 3 — fundal `#F8F9FA` (light) / `#0F1115` (dark), accent albastru `#1E3A8A`; dynamic color dezactivat, pentru o identitate vizuală constantă.

---

## 9. Fluxuri principale

**Autentificarea**
`AuthActivity` → sesiune existentă? → da: `MainActivity`. Nu: formular → validare → `AuthRepository` → n8n (`/auth/login` sau `/users`) sau fallback local → sesiune în DataStore → Toast „Bine ai venit, …!” → `MainActivity` + `finish()`.

**Deconectarea**
Meniul „⋮” din Feed sau Setări → `AuthRepository.logout()` șterge sesiunea → `MainActivity` observă `isLoggedIn = false` → `AuthActivity` (task curățat).

**Prima pornire**
Onboarding → alegere categorii + minute → salvare DataStore → navigare la Feed (Onboarding scos din back stack).

**Încărcarea feed-ului**
`FeedViewModel` citește preferințele → ia ID-urile citite din Room → `GET /news` cu categorii, minute, exclude → salvează în Room → afișează. Fără rețea → pachet construit din cache.

**Citirea unui articol**
Tap pe card → `detail/{id}` → articolul vine din Room → marcat ca citit → nu mai apare la următoarea încărcare a feed-ului.

**Notificarea zilnică**
Setări → switch activ → (permisiune) → `NotificationScheduler` → la ora programată `DailyNewsNotificationWorker` construiește pachetul și trimite notificarea.

---

## 10. Configurare și rulare

### Backend

1. `cp backend/.env.example backend/.env` și completează `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `N8N_API_KEY` (fișierul `.env` nu se versionează).
2. `cd backend && docker compose up -d` — pornește PostgreSQL (schema `postgres_schema.sql` se aplică automat la prima pornire) și n8n.
3. Importă cele două fișiere JSON în n8n și configurează credențialele Postgres pe nodurile de bază de date (aceleași valori ca în `.env`).
4. Nodul **Verificare X-API-Key** compară header-ul cu `{{ $env.N8N_API_KEY }}`, deci cheia vine din `backend/.env`.
5. Activează ambele workflow-uri.

### Aplicația

Secretele nu mai sunt în cod. `app/build.gradle.kts` citește din `local.properties` (rădăcina proiectului, ignorat de git) și le injectează în `BuildConfig`. Copiază `local.properties.example` → `local.properties`:

| Cheie | Rol | Dacă lipsește |
|---|---|---|
| `N8N_BASE_URL` | URL-ul webhook-urilor n8n (terminat cu `/`) | `http://10.0.2.2:5678/webhook/` (n8n local din emulator) |
| `N8N_API_KEY` | Valoarea header-ului `X-API-Key` | șir gol (cererile vor fi respinse) |
| `GOOGLE_WEB_CLIENT_ID` | Client ID OAuth de tip *Web application* pentru „Continuă cu Google” | selector demonstrativ de conturi |

**Configurare Google Sign-In (opțional):** în Google Cloud Console creează un client OAuth *Android* (pachet `ro.newsfilter.app` + SHA-1 din `./gradlew signingReport`) și un client *Web application*; ID-ul celui Web merge în `GOOGLE_WEB_CLIENT_ID`. Emulatorul trebuie să fie o imagine „Google Play” cu un cont adăugat. Fără aceste condiții, aplicația nu dă crash: deschide selectorul demonstrativ.

**Tabela `users` pe o bază existentă:** `postgres_schema.sql` rulează automat doar la prima creare a volumului. Pe o bază deja creată, rulează manual blocul `CREATE TABLE IF NOT EXISTS users …` și reimportă `workflow_2_api_webhooks.json`.

`N8N_API_KEY` trebuie să fie identică în `local.properties` și `backend/.env`. După modificare, rulează din nou Gradle sync / build.

`network_security_config.xml` permite HTTP necriptat doar pentru `10.0.2.2`, `localhost` și `127.0.0.1`; restul traficului trebuie să fie HTTPS.

Deschide proiectul în Android Studio și rulează configurația `app` (vezi mai jos nota despre Gradle wrapper).

---

## 11. Probleme identificate și recomandări

### Critice (împiedică funcționarea corectă)

1. **Parametrii SQL nu sunt legați în n8n.** Nodurile *PostgreSQL Insert* (workflow 1) și *Insert Feedback* (workflow 2) folosesc `$1 … $8`, dar nu au setată opțiunea `Query Parameters` / `queryReplacement`. Inserările vor eșua. Trebuie adăugate valorile (ex. `{{$json.title}}, {{$json.source}}, …` și `{{$json.body.article_id}}, {{$json.body.is_positive}}`).
2. **(Parțial)** Endpoint-urile noi `/auth/login` și `/users` răspund cu 403 la cheie greșită; la `/news`, **ramura „false” a verificării cheii API nu e conectată.** O cerere cu cheie greșită nu primește niciun răspuns (rămâne agățată până la timeout). Adăugați un nod *Respond to Webhook* cu 401.
3. ✅ **Rezolvat.** ~~**Fallback-ul offline poate bloca feed-ul.** În `NewsRepositoryImpl.getFilteredNews`, `newsDao.getNewsByCategories(...).collect { … return@collect }` colectează un Flow Room care nu se termină niciodată (`return@collect` doar iese din lambda). Dacă cache-ul e gol, `throw e` nu se atinge și UI-ul rămâne în loading; în plus, `emit` din interiorul `catch` încalcă principiul de *exception transparency* al Flow-urilor. Soluție: `.first()` în loc de `collect`.~~
4. ✅ **Rezolvat.** ~~**Lipsește Gradle wrapper-ul** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) — proiectul nu se poate construi din linie de comandă până nu se regenerează (`gradle wrapper --gradle-version 8.10.2`).~~

### Importante

5. ✅ **Parțial rezolvat.** ~~**Cheia API e în cod**~~ — acum vine din `local.properties` / `backend/.env`. Atenție: cheia din `BuildConfig` ajunge totuși în APK și poate fi extrasă; e potrivită pentru demo, nu pentru producție.
6. **`/categories` și `/feedback` nu verifică `X-API-Key`.**
7. ✅ **Rezolvat.** ~~**Flash de Onboarding la pornire:** `SettingsViewModel.preferences` pornește cu `UserPreferences()` (`onboardingCompleted = false`), deci `startDestination` e inițial Onboarding chiar și pentru utilizatorii existenți. Afișați un splash/loading până la prima valoare reală din DataStore.~~
8. ✅ **Rezolvat.** ~~**Feed-ul nu reacționează la schimbarea categoriilor din Setări** — preferințele sunt citite o singură dată cu `.first()` în `init`.~~
9. ✅ **Rezolvat** (`TimePickerDialog` în Setări + sincronizare cu permisiunea). ~~**Ora notificării nu poate fi schimbată din UI** (textul spune fix 08:00), deși există `updateNotificationTime`. Notificarea nu se reprogramează după reinstalare/repornire decât prin WorkManager (OK), dar switch-ul poate rămâne activ dacă permisiunea e revocată ulterior.~~
10. **Ingestia acoperă doar 2 surse (BBC Tech/Business, în engleză)** și doar categoriile `tech`/`economy`; celelalte 7 categorii din aplicație vor fi goale (în afara datelor seed), iar fallback-ul greedy va returna articole din alte categorii.
11. **Comentariile spun că rezumatul e „generat de AI”**, dar în realitate este snippet-ul RSS curățat de HTML; timpul de citire este calculat tot pe snippet, deci aproape mereu 1 minut.
12. **Nu există teste** (nici unit, nici instrumented), deși `testInstrumentationRunner` e configurat și dependențele de test lipsesc.

### Minore

13. ✅ **Rezolvat.** ~~`Resource<T>` este definit dar nefolosit.~~
14. ✅ **Rezolvat.** ~~`NewsRepositoryImpl` injectează `NetworkMonitor` fără să-l folosească.~~
15. `exportSchema = true` fără `room.schemaLocation` în KSP → avertisment la build.
16. ✅ **Rezolvat.** ~~Moshi folosește și codegen (`@JsonClass`) și `KotlinJsonAdapterFactory` (reflecție) — al doilea poate fi eliminat.~~
17. Scaffold imbricat (rădăcină + fiecare ecran) poate aplica insets de două ori.
18. `isMinifyEnabled = false` în release; regulile ProGuard sunt minimale.
19. ✅ **Rezolvat** (`.gitignore` și `README.md` adăugate). ~~Lipsesc `README.md` și `.gitignore` (proiectul nu este sub git; `local.properties`, `.gradle`, `.idea`, `.kotlin` ar trebui ignorate).~~
20. Iconița notificării este `android.R.drawable.ic_dialog_info` — ar trebui o iconiță proprie, monocromă.
21. **Token-ul Google nu este verificat pe server.** n8n primește doar email-ul și numele; în producție, `idToken`-ul ar trebui validat (ex. endpoint-ul `tokeninfo` sau o bibliotecă JWT) înainte de a crea sesiunea.
22. **Fallback-ul offline acceptă un cont necunoscut** când serverul nu răspunde (decizie deliberată pentru demonstrații). Pentru producție: refuzați autentificarea fără server dacă nu există cont local.

---

## 12. Maparea cerințelor din barem

| Cerință | Punctaj | Implementare | Unde în cod |
|---|---|---|---|
| **≥ 2 Activity-uri** | 10 | `AuthActivity` (LAUNCHER) → `MainActivity`; navigare explicită cu `Intent` + `finish()`, revenire la logout cu `FLAG_ACTIVITY_CLEAR_TASK` | `AuthActivity.kt`, `MainActivity.kt`, `AndroidManifest.xml` |
| **Autentificare + validarea datelor** | 10 | Login / Cont nou; `Patterns.EMAIL_ADDRESS`, parolă ≥ 6, nume obligatoriu; erori în timp real (`isError` + `supportingText`), buton afișare parolă; sesiune în DataStore; sincronizare cu PostgreSQL (`/auth/login`, `/users`, bcrypt) + fallback local | `ui/screens/auth/*`, `data/repository/AuthRepositoryImpl.kt`, `backend/workflow_2_api_webhooks.json` |
| **Evenimente multiple: click, long-click, notificări** | 10 | `combinedClickable` pe `HeroNewsCard` și `CompactNewsCard`: tap → detaliu, apăsare lungă → vibrație + `ArticleActionsSheet` (Salvează, Marchează ca citit, Distribuie, Deschide în browser); notificare zilnică WorkManager + `TimePickerDialog` | `ui/components/NewsCard.kt`, `ui/components/ArticleActionsSheet.kt`, `worker/*`, `ui/screens/settings/SettingsScreen.kt` |
| **Meniu funcțional** | 10 | Meniu „⋮” (`IconButton` + `DropdownMenu`) în antetul Feed: Reîmprospătează pachetul, Resetează istoricul de citire (Room + Snackbar), Deconectare | `ui/screens/feed/FeedScreen.kt` (`FeedOverflowMenu`), `FeedViewModel.resetReadHistory()`, `NewsDao.resetReadHistory()` |
| **Stocare locală + cloud** | 20 | Room (cache, salvate, istoric citire, vot), DataStore (preferințe, sesiune); PostgreSQL prin API REST n8n (articole, utilizatori, feedback) | `data/local/*`, `data/preferences/*`, `data/remote/*`, `NewsRepositoryImpl.kt`, `backend/*` |
| **Cod curat / modularitate** | 20 | Clean Architecture (domain / data / ui / core), MVVM, Hilt, interfețe de repository, KDoc pe clasele-cheie | `AuthActivity`, `AuthViewModel`, `ShakeDetector`, `NewsRepositoryImpl`, `FeedViewModel`, `NotificationScheduler` |
| **Bonus: autentificare Google** | 5 | Credential Manager + `GetSignInWithGoogleOption`; selector demonstrativ dacă lipsesc Play Services / client ID | `core/auth/GoogleSignInHelper.kt`, `AuthActivity.launchGoogleSignIn()`, `AuthScreen.kt` |
| **Bonus: senzor hardware** | 5 | Accelerometru — „Scutură pentru știri noi”: debouncing, vibrație, Snackbar, pachet nou; activ doar când Feed e vizibil; comutator în Setări | `core/sensor/ShakeDetector.kt`, `FeedScreen.kt`, `SettingsScreen.kt` |

### Scenariu de demonstrație (≈ 3 minute)

1. **Pornire** → `AuthActivity`. Apasă „Intră în cont” cu câmpurile goale → erorile de validare în română. Scrie un email invalid → eroarea apare la părăsirea câmpului.
2. **Cont nou** → nume, email, parolă → `MainActivity` (Toast „Bine ai venit, …!”). Rândul apare în tabela `users` (parolă bcrypt).
3. **Feed** → salut personalizat; apăsare lungă pe un card → vibrație + meniul contextual → „Marchează ca citit” (Snackbar). Tap pe alt card → Detaliu.
4. **Meniul „⋮”** → „Resetează istoricul de citire” (Snackbar cu numărul de articole).
5. **Scuturare** → pe emulator: *Extended controls → Virtual sensors → Device pose*, mișcă rapid dispozitivul (sau `adb emu sensor set acceleration 30:0:9.8` de câteva ori) → „Pachet reîmprospătat prin scuturare!”.
6. **Setări** → cont, „Scutură pentru știri noi”, notificarea zilnică + alegerea orei.
7. **Deconectare** → înapoi la `AuthActivity`; „Continuă cu Google” → contul Google (sau selectorul demonstrativ).

