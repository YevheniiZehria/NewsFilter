# NewsFilter

Aplicație Android care îți livrează un **pachet de știri pe măsura timpului pe care îl ai** (2, 5 sau 15 minute), filtrat după domeniile preferate. Backend: **Firebase** (Authentication + Cloud Firestore, plan gratuit Spark) + un job programat în **GitHub Actions** — 0 €/lună, fără server propriu.

Kotlin · Jetpack Compose · Material 3 · Clean Architecture + MVVM · Hilt · Room · DataStore · WorkManager · Firebase Auth · Cloud Firestore · Credential Manager · Gemini

Documentația completă: [DOCUMENTATIE.md](DOCUMENTATIE.md) · Configurare Firebase: [FIREBASE_SETUP.md](FIREBASE_SETUP.md).

## Funcționalități

- **Două Activity-uri:** `AuthActivity` (autentificare, LAUNCHER) → `MainActivity` (Onboarding, Feed, Salvate, Setări, Detaliu).
- **Autentificare Firebase:** email + parolă, cont nou și **Continuă cu Google** (Credential Manager → token ID verificat de Firebase). Datele personale din Firestore sunt protejate de reguli de securitate (`request.auth.uid`). Fără Firebase nu există acces.
- **Feed offline-first:** lista vine din cache-ul Room (deschidere instantanee, funcționează offline); sincronizarea **incrementală** cu Firestore rulează în fundal (la deschidere, pull-to-refresh, la revenirea rețelei și periodic cu WorkManager).
- **Smart Digest:** jobul de sincronizare (GitHub Actions, la 30 min) descarcă 6 feed-uri BBC, extrage textul complet și generează cu Gemini 2-3 idei cheie, etichete, impactul pe cursul valutar și **„Aprofundează subiectul”**.
- **Market Pulse:** EUR/RON, USD/RON, BTC/USD în timp real (listener Firestore), grafic istoric 1 Z – Max cu știrile care au influențat cursul, convertor valutar.
- **Favorite și voturi per cont** în Firestore (`users/{uid}/bookmarks`, `users/{uid}/votes`), restaurate la autentificare.
- **Evenimente multiple:** tap pe card → detaliu; **apăsare lungă** → vibrație + meniu contextual (Salvează, Marchează ca citit, Distribuie, Deschide în browser).
- **Meniu „⋮”:** reîmprospătare, resetarea istoricului de citire, deconectare.
- **Senzor:** „Scutură pentru știri noi” (accelerometru, cu debouncing), comutator în Setări.
- **Notificare zilnică** programată cu WorkManager la ora aleasă (`TimePickerDialog`).

## Maparea cerințelor din barem

| Cerință | Pct. | Implementare |
|---|---|---|
| ≥ 2 Activity-uri | 10 | [`AuthActivity`](app/src/main/java/ro/newsfilter/app/AuthActivity.kt), [`MainActivity`](app/src/main/java/ro/newsfilter/app/MainActivity.kt), [manifest](app/src/main/AndroidManifest.xml) |
| Autentificare + validare | 10 | [`ui/screens/auth/`](app/src/main/java/ro/newsfilter/app/ui/screens/auth/), [`AuthRepositoryImpl`](app/src/main/java/ro/newsfilter/app/data/repository/AuthRepositoryImpl.kt) (Firebase Authentication) |
| Click, long-click, notificări | 10 | [`NewsCard`](app/src/main/java/ro/newsfilter/app/ui/components/NewsCard.kt) (`combinedClickable`), [`ArticleActionsSheet`](app/src/main/java/ro/newsfilter/app/ui/components/ArticleActionsSheet.kt), [`worker/`](app/src/main/java/ro/newsfilter/app/worker/) |
| Meniu funcțional | 10 | `FeedOverflowMenu` în [`FeedScreen`](app/src/main/java/ro/newsfilter/app/ui/screens/feed/FeedScreen.kt) |
| Stocare locală + cloud | 20 | Room + DataStore ([`data/local`](app/src/main/java/ro/newsfilter/app/data/local/), [`data/preferences`](app/src/main/java/ro/newsfilter/app/data/preferences/)) + Cloud Firestore ([`data/remote/firestore`](app/src/main/java/ro/newsfilter/app/data/remote/firestore/), [`firebase/firestore.rules`](firebase/firestore.rules), [`sync/`](sync/)) |
| Cod curat / modularitate | 20 | straturi `domain / data / ui / core`, Hilt, interfețe de repository, KDoc |
| Bonus: Google Sign-In | 5 | [`GoogleSignInHelper`](app/src/main/java/ro/newsfilter/app/core/auth/GoogleSignInHelper.kt) (Credential Manager) + `GoogleAuthProvider` în Firebase |
| Bonus: senzor | 5 | [`ShakeDetector`](app/src/main/java/ro/newsfilter/app/core/sensor/ShakeDetector.kt) |

## Rulare

Pașii completi (proiect Firebase, `google-services.json`, reguli Firestore, secrete GitHub) sunt în [FIREBASE_SETUP.md](FIREBASE_SETUP.md). Pe scurt:

```bash
# 1. app/google-services.json descărcat din consola Firebase (pachet ro.newsfilter.app)
# 2. secretele GitHub FIREBASE_SERVICE_ACCOUNT + GEMINI_API_KEY → Actions → firebase-sync → Run workflow
./gradlew assembleDebug
```

Test local al jobului de sincronizare (fără scrieri în Firestore): `cd sync && npm ci && node index.mjs --dry-run`.

**Scuturarea pe emulator:** *Extended controls → Virtual sensors*, sau `adb emu sensor set acceleration 30:0:9.8` repetat de câteva ori.

Vechiul backend (n8n + PostgreSQL) este arhivat în [`backend/legacy-n8n/`](backend/legacy-n8n/).
