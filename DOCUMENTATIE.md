# NewsFilter — Documentația proiectului

> Aplicație Android care îți livrează un **„pachet de știri” pe măsura timpului pe care îl ai** (2, 5 sau 15 minute), filtrat după categoriile preferate. Backend-ul rulează **100% gratuit** pe **Firebase** (Authentication + Cloud Firestore, planul Spark), alimentat de un job programat în **GitHub Actions** care descarcă știrile, le rezumă cu **Gemini** și actualizează cursurile valutare.

---

## Cuprins

1. [Pe scurt](#1-pe-scurt)
2. [Structura directoarelor](#2-structura-directoarelor)
3. [Stack tehnologic](#3-stack-tehnologic)
4. [Arhitectura generală](#4-arhitectura-generală)
5. [Backend (Firebase + GitHub Actions)](#5-backend-firebase--github-actions)
6. [Modelul de date Firestore](#6-modelul-de-date-firestore)
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
| Backend | Firebase (Auth + Cloud Firestore, plan Spark gratuit) + job Node.js în GitHub Actions (la 30 min) |
| Limba interfeței | Română |
| Activity-uri | 2 — `AuthActivity` (LAUNCHER) și `MainActivity` |
| Autentificare | Firebase Authentication: email + parolă și Google (Credential Manager) |

**Ideea centrală:** utilizatorul alege câte minute are la dispoziție și categoriile preferate; aplicația afișează cele mai recente articole necitite din acele categorii, cu timpul de lectură estimat din textul complet, plus un rezumat AI (idei cheie, etichete, impact pe cursul valutar și o „aprofundare”) și un widget Market Pulse (EUR/RON, USD/RON, BTC/USD cu grafic istoric).

---

## 2. Structura directoarelor

```
NewsFilter/
├── build.gradle.kts              # build top-level (plugin-uri, apply false)
├── settings.gradle.kts           # rootProject = "NewsFilter", include(":app")
├── local.properties              # sdk.dir (local, ignorat de git)
├── FIREBASE_SETUP.md             # ghidul de configurare Firebase + GitHub Actions
├── gradle/libs.versions.toml     # Version Catalog — toate dependențele
├── firebase/
│   ├── firestore.rules           # regulile de securitate Firestore
│   └── firebase.json             # pentru `firebase deploy --only firestore:rules`
├── sync/                         # jobul de sincronizare (Node.js), rulat de GitHub Actions
│   ├── index.mjs                 # orchestrare: cursuri → știri → Firestore
│   └── lib/                      # rss, scrape, gemini, market, util
├── .github/workflows/firebase-sync.yml   # programare la 30 de minute
├── backend/legacy-n8n/           # vechiul backend n8n + PostgreSQL (arhivat, nefolosit)
└── app/
    ├── build.gradle.kts          # + pluginul google-services
    ├── google-services.json      # configurația Firebase (local, ignorat de git)
    └── src/main/java/ro/newsfilter/app/
        ├── AuthActivity.kt          # Activity 1 (LAUNCHER): autentificare
        ├── MainActivity.kt          # Activity 2: experiența principală
        ├── NewsFilterApp.kt
        ├── core/
        │   ├── auth/        SessionManager, GoogleSignInHelper (Credential Manager)
        │   ├── di/          FirebaseModule, DatabaseModule, RepositoryModule
        │   ├── network/     ApiErrors (mesaje de eroare în română)
        │   ├── audio/       redarea audio (TTS) a pachetului de știri
        │   ├── sensor/      ShakeDetector + ShakeToRefreshEffect (accelerometru)
        │   └── util/        NetworkMonitor, ArticleIntents
        ├── data/
        │   ├── local/       AppDatabase, dao/NewsDao, entity/(NewsEntity, EntityMappers)
        │   ├── remote/      firestore/FirestoreMappers (structura colecțiilor + mapare)
        │   ├── preferences/ UserPreferencesRepository (DataStore: preferințe + sesiune)
        │   └── repository/  NewsRepositoryImpl, AuthRepositoryImpl, MarketRepositoryImpl
        ├── domain/
        │   ├── model/       NewsArticle, Category, User, MarketPulse (+ cursuri, istoric)
        │   └── repository/  NewsRepository, AuthRepository, MarketRepository (interfețe)
        ├── worker/          NewsSyncWorker, DailyNewsNotificationWorker, NotificationScheduler
        └── ui/              navigation, screens (auth, onboarding, feed, detail, bookmarks,
                             settings), components (NewsCard, MarketWidgetCard, MarketChart,
                             CurrencyConverter, …), theme
```

---

## 3. Stack tehnologic

Toate versiunile sunt centralizate în `gradle/libs.versions.toml`.

| Domeniu | Bibliotecă | Versiune | Rol |
|---|---|---|---|
| Build | Android Gradle Plugin | 8.7.2 | |
| Build | Gradle | 8.10.2 | |
| Build | KSP | 2.0.21-1.0.28 | procesare adnotări (Hilt, Room) |
| UI | Compose BOM | 2024.10.01 | Compose + Material 3 + icons extended |
| UI | Navigation Compose | 2.8.4 | navigare între ecrane |
| UI | Coil | 2.7.0 | încărcare imagini async cu cache |
| UI | AndroidX Browser | 1.8.0 | Chrome Custom Tabs pt. articolul sursă |
| DI | Hilt | 2.52 | injecție de dependențe (+ hilt-work, hilt-navigation-compose) |
| Cloud | Firebase BoM | 34.0.0 | `firebase-auth` (autentificare), `firebase-firestore` (baza de date cloud) |
| Cloud | kotlinx-coroutines-play-services | 1.9.0 | `await()` pe Task-urile Firebase |
| Persistență | Room | 2.6.1 | cache offline + favorite + citite |
| Persistență | DataStore Preferences | 1.1.1 | preferințele utilizatorului |
| Background | WorkManager | 2.10.0 | sincronizare periodică + notificarea zilnică |
| Async | Coroutines | 1.9.0 | Flow / suspend |
| Autentificare | Credential Manager (`androidx.credentials`) | 1.5.0 | „Continuă cu Google” |
| Autentificare | Google ID (`googleid`) | 1.1.1 | `GetSignInWithGoogleOption`, `GoogleIdTokenCredential` |
| Job sincronizare | Node.js 24 + `firebase-admin`, `fast-xml-parser` | — | RSS, scraping, Gemini, cursuri → Firestore |

---

## 4. Arhitectura generală

```
 ┌───────────────────────────────────────┐
 │ GitHub Actions — la 30 min (gratuit)  │   RSS BBC (6 feed-uri) → scraping → Gemini
 │ sync/index.mjs  (firebase-admin)      │   Frankfurter (BCE) + CoinGecko
 └───────────────────┬───────────────────┘
                     │ scrie (Admin SDK, ocolește regulile)
                     ▼
 ┌───────────────────────────────────────┐   Firebase Authentication
 │ Cloud Firestore (plan Spark, 0 €)     │   (email + parolă, Google)
 │ articles · market · market_history    │            │
 │ users/{uid}/(bookmarks, votes)        │            │ request.auth.uid
 └───────────────────┬───────────────────┘            │
                     │ citiri incrementale, listener în timp real, scrieri personale
                     ▼                                ▼
 ┌───────────────────────────────────────────────────────────────────┐
 │ Aplicația Android                                                 │
 │  UI (Compose) → ViewModel → Repository → Firestore / Room         │
 │                                 DataStore (preferințe + sesiune)  │
 │                                 WorkManager (sincronizare, notif.)│
 └───────────────────────────────────────────────────────────────────┘
```

Nu există server propriu: partea „de server” (descărcare, procesare AI, cursuri) rulează în jobul
programat, iar aplicația citește direct din Firestore, protejată de regulile de securitate.

Pe dispozitiv, arhitectura respectă Clean Architecture:

- **domain** — modele pure (`NewsArticle`, `Category`, `User`, `MarketPulse`…) și contractele `NewsRepository` / `AuthRepository` / `MarketRepository`; fără dependențe de framework.
- **data** — implementările repository-urilor, Firestore (remote), Room (local, sursa unică de adevăr pentru UI), DataStore (preferințe), mapperi Firestore ↔ Domain ↔ Entity.
- **ui** — ecrane Compose + ViewModel-uri Hilt care expun `StateFlow<UiState>`.
- **core** — module Hilt (`FirebaseModule`…), sesiune, erori, senzori, utilitare.

---

## 5. Backend (Firebase + GitHub Actions)

Totul rulează pe planuri gratuite: Firebase **Spark** (Auth nelimitat; Firestore 50.000 citiri și
20.000 scrieri pe zi, 1 GiB stocare) și **GitHub Actions** (nelimitat pe repository public).
Configurarea pas cu pas: [`FIREBASE_SETUP.md`](FIREBASE_SETUP.md).

### 5.1 Jobul de sincronizare — `sync/` (`.github/workflows/firebase-sync.yml`)

Rulează la fiecare 30 de minute (și manual, *Run workflow*), cu secretele `FIREBASE_SERVICE_ACCOUNT`
și `GEMINI_API_KEY`:

1. **Cursuri valutare** (`lib/market.mjs`): EUR/RON și USD/RON de la BCE (Frankfurter), BTC/USD de la
   CoinGecko → `market/pulse`; adaugă un punct în `market_history/{PERECHE}_intraday` (la 30 min,
   păstrat 30 de zile); o dată pe zi actualizează `{PERECHE}_daily` (BCE 5 ani, BTC 365 de zile).
2. **Știri** (`lib/rss.mjs`): 6 feed-uri BBC (tech, economy, sport, science, health, world);
   **categoria = feed-ul**; deduplicare după URL.
3. **Text complet** (`lib/scrape.mjs`): paragrafele articolului (max. 15.000 caractere);
   **timpul de citire** = `max(1, round(cuvinte / 200))`, calculat pe textul complet.
4. **Smart Digest cu Gemini** (`lib/gemini.mjs`, `gemini-flash-lite-latest`): 5 articole per cerere,
   max. 30 per rulare (cota gratuită). Răspuns JSON validat: rezumat, 2-3 idei cheie, etichete,
   `affected_currency` (doar EUR/RON, USD/RON, BTC/USD), `market_sentiment` (UP / DOWN / NEUTRAL),
   `market_impact_note` și `deep_dive` („Context / De ce contează / Ce urmează”). Articolele la care
   AI-ul eșuează rămân cu rezumatul RSS și sunt reîncercate (max. 3 încercări).
5. **Scriere** în `articles/{id}` (ID = primele 20 de caractere din SHA-1(URL)) cu `updatedAt` =
   timestamp de server; articolele mai vechi de 14 zile sunt șterse.

Pentru a rămâne în cotă, jobul nu interoghează colecția `articles`: un index compact (`meta/index`)
reține ID-ul, data publicării și starea AI a fiecărui articol → o rulare costă ~10 citiri.

### 5.2 Autentificare — Firebase Authentication

Email + parolă (parola este gestionată exclusiv de Firebase) și Google (token-ul ID obținut prin
Credential Manager este verificat de Firebase). Profilul este oglindit în `users/{uid}`.

### 5.3 Regulile de securitate — `firebase/firestore.rules`

| Colecție | Citire | Scriere |
|---|---|---|
| `articles`, `market`, `market_history` | orice utilizator autentificat | nimeni (doar jobul, prin Admin SDK) |
| `users/{uid}/**` | doar proprietarul (`request.auth.uid == uid`) | doar proprietarul |
| orice altceva (ex. `meta`) | nimeni | nimeni |

---

## 6. Modelul de date Firestore

| Document | Câmpuri |
|---|---|
| `articles/{id}` | `title`, `source`, `imageUrl`, `summary`, `articleUrl`, `readTimeMinutes`, `category` (slug din `Category`), `publishedAt` (ISO 8601), `fullContent`, `aiSummary`, `aiBullets[]`, `aiTags[]`, `deepDive`, `affectedCurrency`, `marketSentiment`, `marketImpactNote`, `aiProcessed`, `updatedAt` (timestamp) |
| `market/pulse` | `rates.{EUR_RON, USD_RON, BTC_USD}` = `{pair, rate, changePercent, source, updatedAt}` |
| `market_history/{PERECHE}_intraday` | `points: [{t: ms, v: curs}]` — la 30 min, ultimele 30 de zile (graficele 1 Z / 5 Z / 1 L) |
| `market_history/{PERECHE}_daily` | `points: [{t, v}]` — zilnic, max. 5 ani (graficele 1 A / Max) |
| `users/{uid}` | `name`, `email`, `authProvider`, `createdAt`, `lastLoginAt` |
| `users/{uid}/bookmarks/{articleId}` | copia articolului + `createdAt` (favoritul rămâne chiar dacă știrea e ștearsă de pe server) |
| `users/{uid}/votes/{articleId}` | `positive` (like / dislike), `votedAt` |

**Cum citește aplicația (consum redus de citiri):**
- **Feed:** sincronizare *incrementală* — `articles where updatedAt > ultima_sincronizare orderBy updatedAt` (pagini de 200; prima dată ultimele 200). Filtrarea după categorii și timp se face local, în Room.
- **Cursuri:** listener în timp real pe `market/pulse`, activ doar cât Feed-ul e vizibil (o citire doar când jobul schimbă cursurile).
- **Grafic:** un singur document per pereche/rezoluție, ținut în memorie 30 de minute; seria pentru fiecare interval e agregată local pe „găleți” (10 min / 30 min / 4 h / 1 zi / 7 zile). Știrile cu impact (bannerul și punctele de pe grafic) vin din cache-ul Room.

---

## 7. Aplicația Android — straturi

### 7.1 Punct de intrare

- **`NewsFilterApp`** — `@HiltAndroidApp`; implementează `Configuration.Provider` ca WorkManager să folosească `HiltWorkerFactory`; creează canalul de notificări `daily_news_channel`. Inițializatorul implicit WorkManager este eliminat din manifest.
- **`AuthActivity`** (LAUNCHER) — verifică sesiunea din DataStore; dacă există, deschide imediat `MainActivity`. Altfel afișează `AuthScreen` (login / cont nou / Google). Fluxul Credential Manager e pornit de aici (are nevoie de Activity). La succes: `startActivity(MainActivity)` cu tranziție fade + `finish()`.
- **`MainActivity`** (neexportată) — găzduiește navigarea Compose (`NewsFilterAppContent`). Gardă de sesiune: când `isLoggedIn` devine `false` (Deconectare), revine la `AuthActivity` cu `FLAG_ACTIVITY_CLEAR_TASK`.

### 7.2 `core`

| Fișier | Rol |
|---|---|
| `di/FirebaseModule` | `FirebaseAuth` și `FirebaseFirestore` (cache doar în memorie — cache-ul persistent este Room) |
| `di/DatabaseModule` | Room `newsfilter_db` cu migrări explicite, `NewsDao` |
| `di/RepositoryModule` | `@Binds` pentru `NewsRepository`, `AuthRepository`, `MarketRepository` |
| `auth/SessionManager` | oglindește starea Firebase Auth în DataStore; la deconectare (sau dacă Firebase pierde utilizatorul) șterge sesiunea și cache-ul Room |
| `auth/GoogleSignInHelper` | „Sign in with Google” prin Credential Manager; întoarce `Success(idToken)` / `Cancelled` / `Error(mesaj)` |
| `network/ApiErrors` | `Throwable.toUserMessage()`: erori Firestore / rețea → mesaje în română; `UserFacingException` |
| `sensor/ShakeDetector` | accelerometru: forța g > 2,2 de 2 ori în 600 ms → scuturare; cooldown 2 s (debouncing); `ShakeToRefreshEffect` îl leagă de ciclul de viață (activ doar în RESUMED) |
| `util/NetworkMonitor` | `Flow<Boolean>` de conectivitate (`callbackFlow` + `NetworkCallback`, cerință INTERNET + VALIDATED) |

### 7.3 `domain`

- **`NewsArticle`** — model pur cu `isRead`, `isBookmarked` și proprietăți derivate `categoryLabel`, `categoryEmoji`.
- **`Category`** (enum) — 9 categorii cu slug, etichetă în română și emoji:
  `tech 💻, sport ⚽, economy 📈, politics 🏛️, science 🔬, local 📍, health 🏥, entertainment 🎬, world 🌍`.
- **`NewsRepository`** — contract offline-first: `observeFeed` (Flow din Room), `syncFeed`, `syncBookmarks`, `requestDeepDive`, `sendFeedback`, `setUserVote`, `markAsRead`, `resetReadHistory`, `setBookmarked`, `getBookmarkedArticles`, `getArticleById`, `clearOldCache`.
- **`MarketRepository`** — `observeMarketPulse` (Flow în timp real), `fetchMarketHistory`, `cachedMarketHistory`.

### 7.4 `data`

**Room — tabela `news` (`NewsEntity`)**: aceleași câmpuri ca documentele `articles` + flag-uri locale `is_read`, `is_bookmarked`, `user_vote` și `cached_at`. O singură tabelă servește drept cache, favorite și istoric de citire.

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
| `is_logged_in`, `firebase_uid`, `user_email`, `user_name`, `auth_provider` | oglinda sesiunii Firebase |
| `last_feed_sync_us` | cursorul sincronizării incrementale (cel mai nou `updatedAt`, µs) |

**`AuthRepositoryImpl`** — Firebase Authentication, fără mod offline: `signInWithEmailAndPassword`, `createUserWithEmailAndPassword` (+ numele afișat) și `signInWithCredential(GoogleAuthProvider)`. Erorile Firebase devin mesaje în română (parolă greșită, email folosit, prea multe încercări, fără rețea). La succes scrie profilul în `users/{uid}` (best effort) și sesiunea în DataStore.

**`NewsRepositoryImpl`** — *offline-first*:
1. UI-ul observă doar Room; `syncFeed` aduce din Firestore articolele noi / actualizate (incremental) și le salvează păstrând starea locală (`isRead`, `userVote`, `deepDive`, favorit);
2. favoritele: `users/{uid}/bookmarks` (scriere la salvare, citire la autentificare); voturile: `users/{uid}/votes`;
3. „Aprofundează” citește analiza pre-generată (Room, apoi `articles/{id}`).

**`MarketRepositoryImpl`** — listener pe `market/pulse` combinat cu ultima știre cu impact din Room; istoricul din `market_history`, agregat local.

**Mappers**: `data/remote/firestore/FirestoreMappers.kt` (document Firestore → `NewsArticle`, favorit, cursuri, puncte) și `data/local/entity/EntityMappers.kt` (`NewsArticle` ↔ `NewsEntity`).

### 7.5 `worker`

- **`NewsSyncWorker`** / `NewsSyncScheduler` — sincronizare periodică din Firestore la 3 ore (doar cu rețea și sesiune activă).
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
| **Detaliu** | `DetailViewModel` | citește articolul din Room, îl marchează automat ca citit; imagine, rezumat, buton „deschide articolul” (Custom Tab), like/dislike → `users/{uid}/votes`, bookmark, idei cheie AI, „Aprofundează subiectul” |
| **Salvate** | `BookmarksViewModel` | listă reactivă din Room a favoritelor; eliminare bookmark |
| **Setări** | `SettingsViewModel` | cont (nume, email, metodă) + Deconectare cu confirmare, temă întunecată, timp implicit, categorii, „Scutură pentru știri noi”, notificare zilnică + `TimePickerDialog` |
| **Autentificare** (`AuthActivity`) | `AuthViewModel` | comutator Autentificare / Cont nou, validare în timp real, afișare/ascundere parolă, „Continuă cu Google” (Credential Manager → Firebase) |

**Componente reutilizabile:** `HeroNewsCard` / `CompactNewsCard` (tap + apăsare lungă), `ArticleActionsSheet` (meniu contextual), `CategoryChip` (+ `getCategoryColor`), `TimeChipSelector` (2/5/15), `OfflineBanner`, `ErrorView`, `LoadingShimmer`.

**Temă:** paletă editorială Material 3 — fundal `#F8F9FA` (light) / `#0F1115` (dark), accent albastru `#1E3A8A`; dynamic color dezactivat, pentru o identitate vizuală constantă.

---

## 9. Fluxuri principale

**Autentificarea**
`AuthActivity` → sesiune existentă? → da: `MainActivity`. Nu: formular → validare → `AuthRepository` → Firebase Authentication (email/parolă sau Google) → profil în `users/{uid}` + sesiune în DataStore → Toast „Bine ai venit, …!” → `MainActivity` + `finish()`.

**Deconectarea**
Meniul „⋮” din Feed sau Setări → `AuthRepository.logout()` → `signOut()` Firebase + ștergerea sesiunii și a cache-ului Room → `MainActivity` observă `isLoggedIn = false` → `AuthActivity` (task curățat).

**Prima pornire**
Onboarding → alegere categorii + minute → salvare DataStore → navigare la Feed (Onboarding scos din back stack).

**Încărcarea feed-ului**
`FeedViewModel` afișează imediat articolele necitite din Room → `syncFeed()` aduce din Firestore doar documentele noi → Room emite lista actualizată. Fără rețea → se afișează cache-ul + banner offline.

**Citirea unui articol**
Tap pe card → `detail/{id}` → articolul vine din Room → marcat ca citit → nu mai apare la următoarea încărcare a feed-ului.

**Notificarea zilnică**
Setări → switch activ → (permisiune) → `NotificationScheduler` → la ora programată `DailyNewsNotificationWorker` construiește pachetul și trimite notificarea.

---

## 10. Configurare și rulare

Ghidul complet (în română): [`FIREBASE_SETUP.md`](FIREBASE_SETUP.md). Pe scurt:

1. Proiect Firebase (Spark) → aplicație Android `ro.newsfilter.app` + SHA-1 (`./gradlew signingReport`) → `google-services.json` în `app/` (ignorat de git).
2. Authentication: activează **Email/Password** și **Google**, apoi descarcă din nou `google-services.json`.
3. Firestore: creează baza de date și publică regulile din `firebase/firestore.rules`.
4. GitHub: secretele `FIREBASE_SERVICE_ACCOUNT` (cheia contului de serviciu, JSON) și `GEMINI_API_KEY` → tab-ul Actions → **firebase-sync** → *Run workflow*.
5. `./gradlew :app:assembleDebug` și rulează aplicația — funcționează pe orice rețea, fără `localhost`.

Aplicația nu mai are secrete în `local.properties` (doar `sdk.dir`). `network_security_config.xml` permite exclusiv HTTPS.
Test local al jobului, fără scrieri: `cd sync && npm ci && node index.mjs --dry-run`.

---

## 11. Probleme identificate și recomandări

### Critice (împiedică funcționarea corectă)

1. ~~**Parametrii SQL nu sunt legați în n8n.** Nodurile *PostgreSQL Insert* (workflow 1) și *Insert Feedback* (workflow 2) folosesc `$1 … $8`, dar nu au setată opțiunea `Query Parameters` / `queryReplacement`. Inserările vor eșua. Trebuie adăugate valorile (ex. `{{$json.title}}, {{$json.source}}, …` și `{{$json.body.article_id}}, {{$json.body.is_positive}}`).~~ *(Nu mai este aplicabil: backend-ul n8n a fost înlocuit de Firebase.)*
2. ~~**(Parțial)** Endpoint-urile noi `/auth/login` și `/users` răspund cu 403 la cheie greșită; la `/news`, **ramura „false” a verificării cheii API nu e conectată.** O cerere cu cheie greșită nu primește niciun răspuns (rămâne agățată până la timeout). Adăugați un nod *Respond to Webhook* cu 401.~~ *(Nu mai este aplicabil: backend-ul n8n a fost înlocuit de Firebase.)*
3. ✅ **Rezolvat.** ~~**Fallback-ul offline poate bloca feed-ul.** În `NewsRepositoryImpl.getFilteredNews`, `newsDao.getNewsByCategories(...).collect { … return@collect }` colectează un Flow Room care nu se termină niciodată (`return@collect` doar iese din lambda). Dacă cache-ul e gol, `throw e` nu se atinge și UI-ul rămâne în loading; în plus, `emit` din interiorul `catch` încalcă principiul de *exception transparency* al Flow-urilor. Soluție: `.first()` în loc de `collect`.~~
4. ✅ **Rezolvat.** ~~**Lipsește Gradle wrapper-ul** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) — proiectul nu se poate construi din linie de comandă până nu se regenerează (`gradle wrapper --gradle-version 8.10.2`).~~

### Importante

5. ✅ **Rezolvat.** Nu mai există cheie API în aplicație: accesul la Firestore este controlat de Firebase Auth + regulile de securitate. (Istoric: cheia n8n din `BuildConfig` putea fi extrasă din APK.)
6. ~~**`/categories` și `/feedback` nu verifică `X-API-Key`.**~~ *(Nu mai este aplicabil: backend-ul n8n a fost înlocuit de Firebase.)*
7. ✅ **Rezolvat.** ~~**Flash de Onboarding la pornire:** `SettingsViewModel.preferences` pornește cu `UserPreferences()` (`onboardingCompleted = false`), deci `startDestination` e inițial Onboarding chiar și pentru utilizatorii existenți. Afișați un splash/loading până la prima valoare reală din DataStore.~~
8. ✅ **Rezolvat.** ~~**Feed-ul nu reacționează la schimbarea categoriilor din Setări** — preferințele sunt citite o singură dată cu `.first()` în `init`.~~
9. ✅ **Rezolvat** (`TimePickerDialog` în Setări + sincronizare cu permisiunea). ~~**Ora notificării nu poate fi schimbată din UI** (textul spune fix 08:00), deși există `updateNotificationTime`. Notificarea nu se reprogramează după reinstalare/repornire decât prin WorkManager (OK), dar switch-ul poate rămâne activ dacă permisiunea e revocată ulterior.~~
10. ✅ **Parțial rezolvat.** Ingestia acoperă 6 feed-uri BBC (tech, economy, sport, science, health, world), categoria fiind dată de feed. Categoriile `politics`, `local`, `entertainment` nu au încă o sursă.
11. ✅ **Rezolvat.** Rezumatul, ideile cheie și aprofundarea sunt generate de Gemini, iar timpul de citire se calculează pe textul complet al articolului.
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
21. ~~**Token-ul Google nu este verificat pe server.** n8n primește doar email-ul și numele; în producție, `idToken`-ul ar trebui validat (ex. endpoint-ul `tokeninfo` sau o bibliotecă JWT) înainte de a crea sesiunea.~~ *(Nu mai este aplicabil: backend-ul n8n a fost înlocuit de Firebase.)*
22. ~~**Fallback-ul offline acceptă un cont necunoscut** când serverul nu răspunde (decizie deliberată pentru demonstrații). Pentru producție: refuzați autentificarea fără server dacă nu există cont local.~~ *(Nu mai este aplicabil: backend-ul n8n a fost înlocuit de Firebase.)*

---

## 12. Maparea cerințelor din barem

| Cerință | Punctaj | Implementare | Unde în cod |
|---|---|---|---|
| **≥ 2 Activity-uri** | 10 | `AuthActivity` (LAUNCHER) → `MainActivity`; navigare explicită cu `Intent` + `finish()`, revenire la logout cu `FLAG_ACTIVITY_CLEAR_TASK` | `AuthActivity.kt`, `MainActivity.kt`, `AndroidManifest.xml` |
| **Autentificare + validarea datelor** | 10 | Login / Cont nou; `Patterns.EMAIL_ADDRESS`, parolă ≥ 6, nume obligatoriu; erori în timp real (`isError` + `supportingText`), buton afișare parolă; conturi în Firebase Authentication (parola gestionată de Firebase), profil în Firestore `users/{uid}`, sesiune oglindită în DataStore | `ui/screens/auth/*`, `data/repository/AuthRepositoryImpl.kt`, `core/auth/SessionManager.kt` |
| **Evenimente multiple: click, long-click, notificări** | 10 | `combinedClickable` pe `HeroNewsCard` și `CompactNewsCard`: tap → detaliu, apăsare lungă → vibrație + `ArticleActionsSheet` (Salvează, Marchează ca citit, Distribuie, Deschide în browser); notificare zilnică WorkManager + `TimePickerDialog` | `ui/components/NewsCard.kt`, `ui/components/ArticleActionsSheet.kt`, `worker/*`, `ui/screens/settings/SettingsScreen.kt` |
| **Meniu funcțional** | 10 | Meniu „⋮” (`IconButton` + `DropdownMenu`) în antetul Feed: Reîmprospătează pachetul, Resetează istoricul de citire (Room + Snackbar), Deconectare | `ui/screens/feed/FeedScreen.kt` (`FeedOverflowMenu`), `FeedViewModel.resetReadHistory()`, `NewsDao.resetReadHistory()` |
| **Stocare locală + cloud** | 20 | Room (cache, salvate, istoric citire, vot), DataStore (preferințe, sesiune); **Cloud Firestore** (articole, cursuri + istoric, profil, favorite, voturi) protejat de reguli de securitate | `data/local/*`, `data/preferences/*`, `data/remote/firestore/*`, `NewsRepositoryImpl.kt`, `MarketRepositoryImpl.kt`, `firebase/firestore.rules`, `sync/` |
| **Cod curat / modularitate** | 20 | Clean Architecture (domain / data / ui / core), MVVM, Hilt, interfețe de repository, KDoc pe clasele-cheie | `AuthActivity`, `AuthViewModel`, `ShakeDetector`, `NewsRepositoryImpl`, `FeedViewModel`, `NotificationScheduler` |
| **Bonus: autentificare Google** | 5 | Credential Manager + `GetSignInWithGoogleOption` → token ID verificat de Firebase (`GoogleAuthProvider`) | `core/auth/GoogleSignInHelper.kt`, `AuthViewModel.signInWithGoogle()`, `AuthRepositoryImpl.kt` |
| **Bonus: senzor hardware** | 5 | Accelerometru — „Scutură pentru știri noi”: debouncing, vibrație, Snackbar, pachet nou; activ doar când Feed e vizibil; comutator în Setări | `core/sensor/ShakeDetector.kt`, `FeedScreen.kt`, `SettingsScreen.kt` |

### Scenariu de demonstrație (≈ 3 minute)

1. **Pornire** → `AuthActivity`. Apasă „Intră în cont” cu câmpurile goale → erorile de validare în română. Scrie un email invalid → eroarea apare la părăsirea câmpului.
2. **Cont nou** → nume, email, parolă → `MainActivity` (Toast „Bine ai venit, …!”). Contul apare în Firebase Console → Authentication, iar profilul în Firestore `users/{uid}`.
3. **Feed** → salut personalizat; apăsare lungă pe un card → vibrație + meniul contextual → „Marchează ca citit” (Snackbar). Tap pe alt card → Detaliu.
4. **Meniul „⋮”** → „Resetează istoricul de citire” (Snackbar cu numărul de articole).
5. **Scuturare** → pe emulator: *Extended controls → Virtual sensors → Device pose*, mișcă rapid dispozitivul (sau `adb emu sensor set acceleration 30:0:9.8` de câteva ori) → „Pachet reîmprospătat prin scuturare!”.
6. **Setări** → cont, „Scutură pentru știri noi”, notificarea zilnică + alegerea orei.
7. **Deconectare** → înapoi la `AuthActivity`; „Continuă cu Google” → selectorul de conturi Google → autentificare Firebase.

