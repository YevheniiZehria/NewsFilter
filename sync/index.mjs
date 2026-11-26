/**
 * NewsFilter — job de sincronizare (rulat de GitHub Actions la 30 de minute, gratuit).
 *
 * Înlocuiește workflow-ul n8n de ingestie: cursuri valutare → `market/pulse` + `market_history/*`,
 * apoi RSS BBC → scraping → Gemini (Smart Digest + aprofundare) → `articles/*` în Cloud Firestore.
 *
 * Pentru a rămâne în limitele gratuite (Spark: 50k citiri / 20k scrieri pe zi) jobul nu
 * interoghează colecția `articles`: ține un index compact în `meta/index` (ID → data publicării,
 * starea AI), deci o rulare costă câteva citiri și scrie doar articolele noi / reprocesate.
 *
 * Variabile de mediu: FIREBASE_SERVICE_ACCOUNT (JSON-ul cheii contului de serviciu),
 * GEMINI_API_KEY (opțională). `--dry-run` (sau DRY_RUN=1): fără Firestore, fără Gemini.
 */
import { cert, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { fetchAllFeeds } from './lib/rss.mjs';
import { scrapeArticles } from './lib/scrape.mjs';
import { digestArticles } from './lib/gemini.mjs';
import { appendPoint, fetchMarketRates, mergeDaily } from './lib/market.mjs';
import { DAY_MS, pairKey } from './lib/util.mjs';

const DRY_RUN = process.argv.includes('--dry-run') || process.env.DRY_RUN === '1';
const GEMINI_API_KEY = DRY_RUN ? '' : (process.env.GEMINI_API_KEY || '').trim();

const ARTICLE_TTL_MS = 14 * DAY_MS;       // articolele mai vechi sunt șterse
const INTRADAY_TTL_MS = 30 * DAY_MS;      // puncte la 30 min (graficele 1 Z / 5 Z / 1 L)
const DAILY_TTL_MS = 5 * 365 * DAY_MS;    // puncte zilnice (graficele 1 A / Max)
const MAX_NEW_PER_RUN = 150;              // articole noi scrise / rulare
const MAX_AI_PER_RUN = 30;                // articole trimise la Gemini / rulare (cota gratuită)
const MAX_AI_ATTEMPTS = 3;                // după atâtea eșecuri articolul rămâne cu rezumatul RSS

const now = Date.now();
if (!DRY_RUN && !GEMINI_API_KEY) {
  console.warn('Atenție: lipsește GEMINI_API_KEY — articolele se salvează fără rezumat AI (în română).');
}
const db = DRY_RUN ? null : initFirestore();

function initFirestore() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) throw new Error('Lipsește FIREBASE_SERVICE_ACCOUNT (JSON-ul cheii contului de serviciu).');
  let account;
  try {
    account = JSON.parse(raw);
  } catch {
    throw new Error('FIREBASE_SERVICE_ACCOUNT nu este JSON valid: lipește tot conținutul fișierului cheii, inclusiv acoladele.');
  }
  initializeApp({ credential: cert(account) });
  return getFirestore();
}

async function readDoc(path) {
  if (!db) return {};
  const snap = await db.doc(path).get();
  return snap.exists ? snap.data() : {};
}

/** Scrie operațiile în loturi de max. 450 (limita Firestore este 500 / batch). */
async function commitAll(ops) {
  if (!db || ops.length === 0) return;
  for (let i = 0; i < ops.length; i += 450) {
    const batch = db.batch();
    for (const op of ops.slice(i, i + 450)) op(batch);
    await batch.commit();
  }
}

// ---------------------------------------------------------------- Cursuri valutare
async function syncMarket(state) {
  // Ciornă: momentele descărcării istoricului lung se salvează doar dacă scrierea reușește.
  const draft = { ...state };
  const rates = await fetchMarketRates(draft, now);
  if (rates.length === 0) return;
  const updatedAt = new Date(now).toISOString();

  if (DRY_RUN) {
    for (const r of rates) console.log(`  ${r.pair} = ${r.rate} (${r.changePercent ?? '–'}%) istoric: ${r.history?.length ?? 0} puncte`);
    return;
  }

  const ops = [];
  // Hartă pe perechi + merge: o sursă care a eșuat își păstrează ultimul curs.
  const pulse = {};
  for (const r of rates) {
    pulse[pairKey(r.pair)] = { pair: r.pair, rate: r.rate, changePercent: r.changePercent, source: r.source, updatedAt };
  }
  ops.push((b) => b.set(db.doc('market/pulse'), { rates: pulse, updatedAt: FieldValue.serverTimestamp() }, { merge: true }));

  const intradayRefs = rates.map((r) => db.doc(`market_history/${pairKey(r.pair)}_intraday`));
  const intradaySnaps = await db.getAll(...intradayRefs);
  rates.forEach((r, i) => {
    const points = appendPoint(intradaySnaps[i].data()?.points, now, r.rate, INTRADAY_TTL_MS);
    ops.push((b) => b.set(intradayRefs[i], { pair: r.pair, points }));
  });

  for (const r of rates.filter((x) => x.history?.length)) {
    const ref = db.doc(`market_history/${pairKey(r.pair)}_daily`);
    const existing = (await ref.get()).data()?.points;
    const points = mergeDaily(existing, r.history, now, DAILY_TTL_MS);
    ops.push((b) => b.set(ref, { pair: r.pair, points }));
  }

  await commitAll(ops);
  Object.assign(state, draft);
  console.log(`Cursuri salvate: ${rates.map((r) => r.pair).join(', ')}`);
}

// ---------------------------------------------------------------- Știri
/** @return indexul actualizat, doar după ce articolele au fost scrise (altfel aruncă eroare). */
async function syncNews(current) {
  // Copie: dacă scrierea eșuează, `meta/index` rămâne cel vechi (articolele se reîncearcă).
  const index = { ...current };
  const isAlive = (v) => Date.parse(v.p) >= now - ARTICLE_TTL_MS;
  const feed = await fetchAllFeeds();
  const fresh = feed
    .filter((a) => !index[a.id] && Date.parse(a.publishedAt) > now - ARTICLE_TTL_MS)
    .sort((a, b) => b.publishedAt.localeCompare(a.publishedAt))
    .slice(0, MAX_NEW_PER_RUN);
  console.log(`RSS: ${feed.length} articole, ${fresh.length} noi`);

  await scrapeArticles(fresh);
  for (const a of fresh) {
    Object.assign(a, {
      aiSummary: null, aiBullets: [], aiTags: [], deepDive: null,
      affectedCurrency: null, marketSentiment: null, marketImpactNote: null, aiProcessed: false
    });
  }

  // Lista pentru Gemini: întâi articolele noi, apoi cele vechi rămase neprocesate (reîncercări).
  let aiQueue = [];
  let retries = [];
  let attempted = new Set();
  if (GEMINI_API_KEY) {
    aiQueue = fresh.slice(0, MAX_AI_PER_RUN);
    // Cele mai noi întâi; fără cele care expiră în mai puțin de o zi.
    const retryIds = Object.entries(index)
      .filter(([, v]) => !v.ai && (v.n || 0) < MAX_AI_ATTEMPTS && Date.parse(v.p) > now - ARTICLE_TTL_MS + DAY_MS)
      .sort(([, a], [, b]) => String(b.p).localeCompare(String(a.p)))
      .map(([id]) => id)
      .slice(0, MAX_AI_PER_RUN - aiQueue.length);
    if (db && retryIds.length) {
      const snaps = await db.getAll(...retryIds.map((id) => db.doc(`articles/${id}`)));
      retries = snaps.filter((s) => s.exists).map((s) => ({ id: s.id, ...s.data() }));
      // Documentele dispărute nu mai trebuie reîncercate.
      for (const id of retryIds) if (!retries.some((r) => r.id === id)) delete index[id];
    }
    aiQueue = aiQueue.concat(retries);
    const result = await digestArticles(GEMINI_API_KEY, aiQueue);
    attempted = result.attempted;
    console.log(`Gemini: ${result.ok}/${aiQueue.length} articole procesate`);
  }

  const ops = [];
  const toDoc = ({ id, ...rest }) => ({ ...rest, updatedAt: FieldValue.serverTimestamp() });
  for (const a of fresh) {
    index[a.id] = { p: a.publishedAt, ai: a.aiProcessed, n: attempted.has(a) ? 1 : 0 };
    if (db) ops.push((b) => b.set(db.doc(`articles/${a.id}`), toDoc(a)));
  }
  for (const a of retries) {
    index[a.id] = { ...index[a.id], ai: a.aiProcessed, n: (index[a.id]?.n || 0) + (attempted.has(a) ? 1 : 0) };
    if (db && a.aiProcessed) ops.push((b) => b.set(db.doc(`articles/${a.id}`), toDoc(a)));
  }

  // Curățare: articole mai vechi de 14 zile.
  // Dată lipsă / invalidă → tratată ca expirată (altfel intrarea n-ar mai fi ștearsă niciodată).
  const expired = Object.entries(index).filter(([, v]) => !isAlive(v)).map(([id]) => id);
  for (const id of expired) {
    delete index[id];
    if (db) ops.push((b) => b.delete(db.doc(`articles/${id}`)));
  }

  await commitAll(ops);
  console.log(`Articole: ${fresh.length} noi${DRY_RUN ? " (dry-run, nimic scris)" : " scrise"}, ${retries.filter((r) => r.aiProcessed).length} reprocesate, ${expired.length} șterse`);

  if (DRY_RUN) {
    for (const a of fresh.slice(0, 5)) {
      console.log(`  [${a.category}] ${a.title} — ${a.readTimeMinutes} min, text complet: ${a.fullContent ? a.fullContent.length + ' caractere' : 'nu'}`);
    }
    const perCategory = {};
    for (const a of feed) perCategory[a.category] = (perCategory[a.category] || 0) + 1;
    console.log(`  Pe categorii: ${JSON.stringify(perCategory)}`);
  }
  return index;
}

// ---------------------------------------------------------------- Main
const state = await readDoc('meta/sync');
let index = (await readDoc('meta/index')).articles || {};
let failed = false;

try {
  await syncMarket(state);
} catch (e) {
  failed = true;
  console.error('Eroare cursuri valutare:', e);
}
try {
  index = await syncNews(index);
} catch (e) {
  failed = true;
  console.error('Eroare știri:', e);
}

if (db) {
  await commitAll([
    (b) => b.set(db.doc('meta/sync'), { ...state, lastRunAt: FieldValue.serverTimestamp() }),
    (b) => b.set(db.doc('meta/index'), { articles: index })
  ]);
}
if (failed) process.exitCode = 1;
