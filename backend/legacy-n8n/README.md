# Backend vechi (n8n + PostgreSQL) — arhivat

Acesta este backend-ul folosit de NewsFilter înainte de migrarea pe **Firebase** (vezi
[FIREBASE_SETUP.md](../../FIREBASE_SETUP.md)). Nu mai este folosit de aplicație și este păstrat
doar ca referință (documentația proiectului îl descrie în secțiunea despre istoricul backend-ului).

| Fișier | Rol (în vechea arhitectură) | Înlocuit de |
|---|---|---|
| `workflow_1_ingestion_and_processing.json` | RSS BBC → scraping → Gemini → `articles`; cursuri valutare la 1 min | `sync/` (GitHub Actions, la 30 min) |
| `workflow_2_api_webhooks.json` | API REST (auth, știri, favorite, feedback, deep-dive, market) | Firebase Auth + citiri directe din Firestore |
| `postgres_schema.sql` | Schema PostgreSQL | Colecțiile Firestore (`firebase/firestore.rules`) |
| `docker-compose.yml`, `start-n8n.ps1`, `.env.example` | Rulare locală n8n + Postgres | — |

Pentru a-l porni totuși local: `docker compose up -d` în acest director (cu `.env` completat după
`.env.example`), apoi importă manual cele două workflow-uri în n8n.
