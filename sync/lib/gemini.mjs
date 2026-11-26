import { fetchWithTimeout, sleep } from './util.mjs';

const MODEL = 'gemini-flash-lite-latest';
/** Articole per apel (un singur apel JSON pentru tot lotul → mai puține cereri din cota gratuită). */
const BATCH_SIZE = 5;
const CONTENT_CHARS = 6000;
const PAUSE_MS = 4500; // ~13 cereri/minut, sub limita gratuită de 15 RPM

/** Singurele perechi acceptate (identice cu cele din aplicație: `MarketPairs.all`). */
const MARKET_PAIRS = ['EUR/RON', 'USD/RON', 'BTC/USD'];
const SENTIMENTS = ['UP', 'DOWN', 'NEUTRAL'];

const cleanList = (value, max, maxLen) => (Array.isArray(value) ? value : [])
  .map((v) => String(v || '').replace(/^[-•*\s]+/, '').trim())
  .filter((v) => v.length > 0)
  .map((v) => v.slice(0, maxLen))
  .slice(0, max);

function buildPrompt(batch) {
  const items = batch.map((a) => ({
    id: a.id,
    titlu: a.title,
    continut: (a.fullContent || a.summary || '').slice(0, CONTENT_CHARS)
  }));
  return `Esti redactor si analist de stiri. Pentru FIECARE articol din lista JSON de mai jos raspunde DOAR cu un array JSON valid, in limba ROMANA, cu cate un obiect per articol (acelasi "id"):
[{"id": "...", "summary": "rezumat de 3-4 propozitii", "bullets": ["idee cheie 1", "idee cheie 2", "idee cheie 3"], "tags": ["eticheta 1", "eticheta 2"], "affected_currency": "EUR/RON", "market_sentiment": "UP", "market_impact_note": "o propozitie", "deep_dive": "Context: ...\\nDe ce conteaza: ...\\nCe urmeaza: ..."}]
Reguli:
- "bullets" are 2-3 elemente, fiecare sub 20 de cuvinte, cu informatia esentiala; "tags" are 2-4 etichete scurte (1-2 cuvinte, litere mici) despre subiecte, persoane, locuri sau organizatii. Nu inventa informatii.
- "affected_currency" este UNA dintre perechile EUR/RON, USD/RON, BTC/USD, doar daca stirea poate influenta clar cursul acelei perechi; altfel null. "market_sentiment" este directia probabila a perechii: "UP" = prima moneda se apreciaza fata de a doua, "DOWN" = se depreciaza, "NEUTRAL" = impact neclar (obligatoriu "NEUTRAL" cand affected_currency este null). "market_impact_note" este o singura propozitie (sub 25 de cuvinte) care explica impactul, sau null cand affected_currency este null.
- "deep_dive" este o aprofundare de 150-220 de cuvinte cu exact trei sectiuni, fiecare pe un rand nou, incepand cu titlul urmat de doua puncte: "Context:", "De ce conteaza:", "Ce urmeaza:". Foloseste doar informatii din articol si cunostinte generale sigure; nu inventa cifre, nume sau citate; fara markdown.

Articole:
${JSON.stringify(items)}`;
}

/** Aplică rezultatul AI pe articol (aceleași reguli de validare ca vechiul workflow n8n). */
function applyDigest(article, parsed) {
  const summary = String(parsed.summary || '').trim();
  const bullets = cleanList(parsed.bullets, 3, 220);
  const tags = cleanList(parsed.tags, 4, 30).map((t) => t.toLowerCase());
  const deepDive = String(parsed.deep_dive || '').replace(/\*\*/g, '').trim();
  if (!summary || bullets.length < 2) return false;

  article.aiSummary = summary;
  article.aiBullets = bullets;
  article.aiTags = tags;
  article.deepDive = deepDive || null;

  const pair = String(parsed.affected_currency || '').toUpperCase().replace(/\s+/g, '');
  const sentiment = String(parsed.market_sentiment || '').toUpperCase();
  if (MARKET_PAIRS.includes(pair)) {
    article.affectedCurrency = pair;
    article.marketSentiment = SENTIMENTS.includes(sentiment) ? sentiment : 'NEUTRAL';
    article.marketImpactNote = String(parsed.market_impact_note || '').trim().slice(0, 220) || null;
  } else {
    // „Analizat, fără impact”: NEUTRAL și fără pereche.
    article.affectedCurrency = null;
    article.marketSentiment = 'NEUTRAL';
    article.marketImpactNote = null;
  }
  article.aiProcessed = true;
  return true;
}

async function digestBatch(apiKey, batch) {
  const res = await fetchWithTimeout(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: 'POST',
      timeoutMs: 90_000,
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify({
        contents: [{ parts: [{ text: buildPrompt(batch) }] }],
        generationConfig: { temperature: 0.3, maxOutputTokens: 8192, responseMimeType: 'application/json' }
      })
    }
  );
  const data = await res.json();
  const raw = (data?.candidates?.[0]?.content?.parts?.[0]?.text || '').replace(/^```(?:json)?|```$/g, '').trim();
  const parsed = JSON.parse(raw);
  const byId = new Map((Array.isArray(parsed) ? parsed : []).map((p) => [String(p?.id), p]));
  let ok = 0;
  for (const article of batch) {
    const p = byId.get(article.id);
    if (p && applyDigest(article, p)) ok++;
  }
  return ok;
}

/**
 * Smart Digest pentru articole: rezumat + idei cheie + etichete + impact valutar + aprofundare.
 * Erorile (cotă, JSON invalid) lasă articolul neprocesat (`aiProcessed` rămâne false) → reîncercat
 * la rularea următoare.
 */
export async function digestArticles(apiKey, articles) {
  let ok = 0;
  for (let i = 0; i < articles.length; i += BATCH_SIZE) {
    if (i > 0) await sleep(PAUSE_MS);
    const batch = articles.slice(i, i + BATCH_SIZE);
    try {
      ok += await digestBatch(apiKey, batch);
    } catch (e) {
      console.warn(`Gemini: lot eșuat (${e.message})`);
    }
  }
  return ok;
}
