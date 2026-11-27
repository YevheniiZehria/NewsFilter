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
 * GEMINI_API_KEY (opțională). `--dry-run` (sau DRY_RUN=1): fără Firestore, fără Gemini, fără FCM
 * (alertele care s-ar trimite sunt doar afișate; ALERT_THRESHOLD_PCT=0.1 le forțează pentru test).
 */
import { cert, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { fetchAllFeeds } from './lib/rss.mjs';
import { scrapeArticles } from './lib/scrape.mjs';
import { buildPrompt, digestArticles } from './lib/gemini.mjs';
import { appendPoint, fetchMarketRates, mergeDaily } from './lib/market.mjs';
import { newsAlert, rateAlerts, sendAlerts } from './lib/notify.mjs';
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
const db = DRY_RUN ? null : initFirestore();

function initFirestore() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) throw new Error('Lipsește FIREBASE_SERVICE_ACCOUNT (JSON-ul cheii contului de serviciu).');
  initializeApp({ credential: cert(JSON.parse(raw)) });
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
const SLOT_MS = 30 * 60_000;

/** Ultimul punct intraday dinaintea slotului curent = cursul de la rularea anterioară. */
const previousPoint = (points) => (points || []).filter((p) => p.t < Math.floor(now / SLOT_MS) * SLOT_MS).at(-1)?.v;

/** @returns [{ pair, rate, previous }] pentru alertele de mișcare bruscă */
async function syncMarket(state) {
  const rates = await fetchMarketRates(state, now);
  if (rates.length === 0) return [];
  const updatedAt = new Date(now).toISOString();

  if (DRY_RUN) {
    for (const r of rates) console.log(`  ${r.pair} = ${r.rate} (${r.changePercent ?? '–'}%) istoric: ${r.history?.length ?? 0} puncte`);
    // Fără Firestore: variația raportată de sursă ține locul cursului anterior (doar pentru simulare).
    return rates.map((r) => ({ pair: r.pair, rate: r.rate, previous: r.changePercent != null ? r.rate / (1 + r.changePercent / 100) : null }));
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
  console.log(`Cursuri salvate: ${rates.map((r) => r.pair).join(', ')}`);
  return rates.map((r, i) => ({ pair: r.pair, rate: r.rate, previous: previousPoint(intradaySnaps[i].data()?.points) }));
}

// ---------------------------------------------------------------- Știri
async function syncNews(index) {
  const feed = await fetchAllFeeds();
  const fresh = feed
    .filter((a) => !index[a.id] && Date.parse(a.publishedAt) > now - ARTICLE_TTL_MS)
    .sort((a, b) => b.publishedAt.localeCompare(a.publishedAt))
    .slice(0, MAX_NEW_PER_RUN);
  console.log(`RSS: ${feed.length} articole, ${fresh.length} noi`);

  await scrapeArticles(fresh);
  for (const a of fresh) {
    Object.assign(a, {
      aiTitle: null, aiSummary: null, aiBullets: [], aiTags: [], deepDive: null,
      affectedCurrency: null, marketSentiment: null, marketImpactNote: null, marketImpactStrength: null, aiProcessed: false,
      // Voturile comunității: incrementate doar de aplicație (FieldValue.increment), verificate de reguli.
      upvotes: 0, downvotes: 0
    });
  }

  // Lista pentru Gemini: întâi articolele noi, apoi cele vechi rămase neprocesate (reîncercări).
  let aiQueue = [];
  let retries = [];
  if (GEMINI_API_KEY) {
    aiQueue = fresh.slice(0, MAX_AI_PER_RUN);
    const retryIds = Object.entries(index)
      .filter(([, v]) => !v.ai && (v.n || 0) < MAX_AI_ATTEMPTS)
      .map(([id]) => id)
      .slice(0, MAX_AI_PER_RUN - aiQueue.length);
    if (db && retryIds.length) {
      const snaps = await db.getAll(...retryIds.map((id) => db.doc(`articles/${id}`)));
      retries = snaps.filter((s) => s.exists).map((s) => ({ id: s.id, ...s.data() }));
      // Documentele dispărute nu mai trebuie reîncercate.
      for (const id of retryIds) if (!retries.some((r) => r.id === id)) delete index[id];
    }
    aiQueue = aiQueue.concat(retries);
    const ok = await digestArticles(GEMINI_API_KEY, aiQueue);
    console.log(`Gemini: ${ok}/${aiQueue.length} articole procesate`);
  }

  const ops = [];
  const toDoc = ({ id, ...rest }) => ({ ...rest, updatedAt: FieldValue.serverTimestamp() });
  // Reîncercările scriu DOAR câmpurile AI (merge): restul documentului (text complet, voturi) rămâne neatins.
  const toAiPatch = (a) => ({
    aiTitle: a.aiTitle ?? null, aiSummary: a.aiSummary, aiBullets: a.aiBullets, aiTags: a.aiTags, deepDive: a.deepDive,
    affectedCurrency: a.affectedCurrency, marketSentiment: a.marketSentiment, marketImpactNote: a.marketImpactNote,
    marketImpactStrength: a.marketImpactStrength ?? null,
    aiProcessed: true, updatedAt: FieldValue.serverTimestamp()
  });
  for (const a of fresh) {
    index[a.id] = { p: a.publishedAt, ai: a.aiProcessed, n: aiQueue.includes(a) ? 1 : 0 };
    if (db) ops.push((b) => b.set(db.doc(`articles/${a.id}`), toDoc(a)));
  }
  for (const a of retries) {
    index[a.id] = { ...index[a.id], ai: a.aiProcessed, n: (index[a.id]?.n || 0) + 1 };
    if (db && a.aiProcessed) ops.push((b) => b.set(db.doc(`articles/${a.id}`), toAiPatch(a), { merge: true }));
  }

  // Curățare: articole mai vechi de 14 zile.
  const expired = Object.entries(index).filter(([, v]) => Date.parse(v.p) < now - ARTICLE_TTL_MS).map(([id]) => id);
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
    // Structura promptului Gemini (fără apel): primele 2 articole, conținut scurtat.
    if (fresh.length) {
      const sample = fresh.slice(0, 2).map((a) => ({ ...a, fullContent: (a.fullContent || a.summary || '').slice(0, 160) + '…' }));
      console.log(`\n--- Prompt Gemini (dry-run, exemplu) ---\n${buildPrompt(sample)}\n--- sfârșit prompt ---`);
    }
  }
  return aiQueue;
}

// ---------------------------------------------------------------- Main
const state = await readDoc('meta/sync');
const index = (await readDoc('meta/index')).articles || {};
let failed = false;
const alerts = [];

try {
  alerts.push(...rateAlerts(await syncMarket(state)));
} catch (e) {
  failed = true;
  console.error('Eroare cursuri valutare:', e);
}
try {
  const alert = newsAlert(await syncNews(index));
  if (alert) alerts.push(alert);
} catch (e) {
  failed = true;
  console.error('Eroare știri:', e);
}

console.log(`Alerte FCM: ${alerts.length}`);
await sendAlerts(alerts, { dryRun: DRY_RUN, messaging: db ? getMessaging() : null });

if (db) {
  await commitAll([
    (b) => b.set(db.doc('meta/sync'), { ...state, lastRunAt: FieldValue.serverTimestamp() }),
    (b) => b.set(db.doc('meta/index'), { articles: index })
  ]);
}
if (failed) process.exitCode = 1;
