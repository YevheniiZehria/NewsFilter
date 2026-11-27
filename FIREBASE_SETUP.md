# Configurare Firebase (gratuit, 0 €/lună)

Ghid pas cu pas pentru a rula NewsFilter online, 24/7, fără server propriu și fără costuri.

**Cum funcționează:**

```
GitHub Actions (la 30 min, gratuit)          Cloud Firestore (plan Spark)          Aplicația Android
 sync/index.mjs                                                                     (Firebase Auth)
  ├─ RSS BBC + scraping          ──scrie──►   articles/{id}                ◄──citește── feed, detaliu
  ├─ Gemini (rezumat, idei,                   market/pulse                 ◄──ascultă── widget valutar
  │  impact valutar, aprofundare)             market_history/{PAIR}_*      ◄──citește── grafic
  └─ Frankfurter + CoinGecko                  users/{uid}/bookmarks|votes  ◄──scrie──── favorite, voturi
```

Ai nevoie de: un cont Google, repository-ul NewsFilter pe GitHub (**public**, ca minutele GitHub
Actions să fie nelimitate) și Android Studio / JDK pentru build.

---

## 1. Creează proiectul Firebase

1. Deschide [console.firebase.google.com](https://console.firebase.google.com) → **Create a project**.
2. Alege un nume (ex. `newsfilter`). Google Analytics nu este necesar (îl poți dezactiva).
3. Proiectul pornește pe planul **Spark (gratuit)**. **Nu** trece pe Blaze — nu este nevoie.

## 2. Adaugă aplicația Android și descarcă `google-services.json`

1. Află amprenta SHA-1 a cheii de debug (necesară pentru „Continuă cu Google”), din rădăcina proiectului:
   ```bash
   ./gradlew signingReport
   ```
   Copiază valoarea `SHA1` din secțiunea `Variant: debug`.
2. În consolă: **Project settings** (⚙) → **Your apps** → pictograma Android.
   - **Package name:** `ro.newsfilter.app`
   - **Debug signing certificate SHA-1:** valoarea de mai sus
3. Descarcă **`google-services.json`** și pune-l în `app/google-services.json`
   (înlocuiește fișierul-substituent, dacă există). Fișierul este ignorat de git.

## 3. Activează autentificarea

1. **Build → Authentication → Get started → Sign-in method**.
2. Activează **Email/Password**.
3. Activează **Google** (alege un *support email*) → Save.
4. **Descarcă din nou `google-services.json`** (pasul 2.3): după activarea Google conține client ID-ul
   web folosit de aplicație (`default_web_client_id`). Fără el, butonul Google afișează
   „Autentificarea Google nu este activată în proiectul Firebase”.

## 4. Creează baza de date Firestore și regulile de securitate

1. **Build → Firestore Database → Create database**.
2. Locație: `eur3 (europe-west)` sau cea mai apropiată regiune; mod: **production**.
3. Tab-ul **Rules**: înlocuiește conținutul cu cel din [`firebase/firestore.rules`](firebase/firestore.rules) → **Publish**.

   Recomandat, cu Firebase CLI (publică regulile **și** `firestore.indexes.json`):
   `cd firebase && npx firebase-tools deploy --only firestore --project <ID-proiect>`.
   `firestore.indexes.json` scoate de la indexare câmpul `articles` din `meta/index` (altfel
   documentul ar atinge limita de 40 000 de intrări de index); din consolă se poate face manual la
   **Indexes → Single field → Add exemption** (colecția `meta`, câmpul `articles`, toate indexările dezactivate).

Regulile permit: citirea știrilor și cursurilor doar utilizatorilor autentificați, scrierea lor doar
jobului de sincronizare (Admin SDK) și, doar proprietarului contului, profilul `users/{uid}`,
`users/{uid}/bookmarks/…` și `users/{uid}/votes/…`, cu câmpuri și mărimi validate (orice altă
subcolecție este închisă, ca un singur cont să nu poată consuma cota de scrieri a proiectului).

## 5. Cheile pentru jobul de sincronizare

1. **Cheia contului de serviciu (Firebase Admin):** Project settings → **Service accounts** →
   **Generate new private key** → se descarcă un fișier JSON.
   ⚠️ Este o cheie secretă cu acces complet la baza de date: nu o pune în repository și nu o trimite nimănui.
2. **Cheia Gemini (gratuită):** [aistudio.google.com/apikey](https://aistudio.google.com/apikey) → **Create API key**.
   Opțională: fără ea, articolele apar cu rezumatul RSS (fără idei cheie, impact valutar și aprofundare).

## 6. Activează sincronizarea automată (GitHub Actions)

1. Pe GitHub, în repository: **Settings → Secrets and variables → Actions → New repository secret**:
   | Nume | Valoare |
   |---|---|
   | `FIREBASE_SERVICE_ACCOUNT` | tot conținutul fișierului JSON de la pasul 5.1 |
   | `GEMINI_API_KEY` | cheia de la pasul 5.2 |
2. Tab-ul **Actions** → activează workflow-urile dacă GitHub cere confirmare → **firebase-sync** →
   **Run workflow** (prima rulare manuală).
3. După ~2-5 minute, în consola Firestore apar colecțiile `articles`, `market`, `market_history` și `meta`.
   Prima rulare încarcă și istoricul cursurilor (5 ani BCE, 365 de zile BTC). Gemini procesează max.
   30 de articole per rulare, deci rezumatele AI pentru toate știrile apar în următoarele rulări.

De acum, jobul rulează singur la fiecare 30 de minute (GitHub poate întârzia rulările programate cu câteva minute).

## 7. Compilează și rulează aplicația

```bash
./gradlew :app:assembleDebug   # necesită JDK 17+ (JAVA_HOME) sau build din Android Studio
```

Instalează APK-ul (`app/build/outputs/apk/debug/`), creează un cont (email sau Google) și gata —
aplicația funcționează pe orice rețea, fără `localhost`.

---

## Limite gratuite și consum estimat

| Resursă | Limită gratuită | Consum NewsFilter |
|---|---|---|
| Firestore — citiri | 50.000 / zi | job: ~10 / rulare (~500 / zi); utilizator: câteva sute / zi (sincronizare incrementală, cursuri în timp real doar cu ecranul deschis) |
| Firestore — scrieri | 20.000 / zi | job: articolele noi + ~5 documente / rulare (~1.000-2.000 / zi) |
| Firestore — stocare | 1 GiB | < 50 MB (articolele mai vechi de 14 zile sunt șterse automat) |
| Firebase Auth | nelimitat (email + Google) | — |
| GitHub Actions | nelimitat pe repository public | ~1-2 min / rulare |
| Gemini API | cota gratuită a modelului `gemini-flash-lite-latest` | ~6 cereri / rulare (5 articole per cerere) |

## Probleme frecvente

- **Butonul Google afișează o eroare** → lipsește SHA-1 (pasul 2) sau `google-services.json` a fost
  descărcat înainte de activarea Google (pasul 3.4). Pentru build-ul *release*, adaugă și SHA-1-ul cheii de release.
- **Feed gol după autentificare** → jobul nu a rulat încă: verifică tab-ul Actions (pasul 6) și colecția `articles`.
- **„Sesiunea a expirat”** la încărcarea datelor → regulile de securitate nu au fost publicate (pasul 4.3).
- **Jobul nu mai rulează** → GitHub dezactivează workflow-urile programate după 60 de zile fără commit-uri
  în repository: reactivează-l din tab-ul Actions (sau fă un commit).
- **Test local al jobului** (fără scrieri în Firestore și fără Gemini):
  ```bash
  cd sync && npm ci && node index.mjs --dry-run
  ```
  Rulare reală locală: setează variabilele `FIREBASE_SERVICE_ACCOUNT` (conținutul JSON) și `GEMINI_API_KEY`, apoi `node index.mjs`.
