import { fetchWithTimeout, sleep } from './util.mjs';

const MODEL = 'gemini-flash-lite-latest';
/** Articole per apel (un singur apel JSON pentru tot lotul → mai puține cereri din cota gratuită). */
const BATCH_SIZE = 5;
const CONTENT_CHARS = 6000;
const PAUSE_MS = 4500; // ~13 cereri/minut, sub limita gratuită de 15 RPM
const REQUEST_TIMEOUT_MS = 45_000;
/** Buget total pentru Gemini într-o rulare (jobul are 20 min; restul e RSS, scraping, scrieri). */
const TOTAL_BUDGET_MS = 8 * 60_000;

/** Schema răspunsului: forțează un array de obiecte (fără `{"articles": [...]}` sau alte învelișuri). */
const RESPONSE_SCHEMA = {
  type: 'ARRAY',
  items: {
    type: 'OBJECT',
    properties: {
      id: { type: 'STRING' },
      summary: { type: 'STRING' },
      bullets: { type: 'ARRAY', items: { type: 'STRING' } },
      tags: { type: 'ARRAY', items: { type: 'STRING' } },
      affected_currency: { type: 'STRING', nullable: true },
      market_sentiment: { type: 'STRING', enum: ['UP', 'DOWN', 'NEUTRAL'] },
      market_impact_note: { type: 'STRING', nullable: true },
      deep_dive: { type: 'STRING' }
    },
    required: ['id', 'summary', 'bullets', 'tags', 'market_sentiment', 'deep_dive']
  }
};

/**
 * Eroare HTTP = problemă a serviciului sau a configurației (429 cotă, 5xx, 400 cerere/model
 * invalid, 401/403 cheie), nu a articolelor → oprim restul loturilor fără a consuma încercări.
 */
const isFatal = (e) => typeof e?.status === 'number';

/** Singurele perechi acceptate (identice cu cele din aplicație: `MarketPairs.all`). */
const MARKET_PAIRS = ['EUR/RON', 'USD/RON', 'BTC/USD'];
const SENTIMENTS = ['UP', 'DOWN', 'NEUTRAL'];

const cleanList = (value, max, maxLen) => (Array.isArray(value) ? value : [])
  .map((v) => String(v || '').replace(/^[-•*\s]+/, '').trim())
  .filter((v) => v.length > 0)
  .map((v) => v.slice(0, maxLen))
  .slice(0, max);

export function buildPrompt(batch) {
  const items = batch.map((a) => ({
    id: a.id,
    titlu: a.title,
    continut: (a.fullContent || a.summary || '').slice(0, CONTENT_CHARS)
  }));
  return `Esti redactor si analist de stiri pentru un public din Romania. Articolele sunt in limba ENGLEZA; traduce-le si redacteaza-le in limba ROMANA. Pentru FIECARE articol din lista JSON de mai jos raspunde DOAR cu un array JSON valid, cu cate un obiect per articol (acelasi "id"):
[{"id": "...", "title": "titlul tradus in romana", "summary": "rezumat de 3-4 propozitii", "bullets": ["idee cheie 1", "idee cheie 2", "idee cheie 3"], "tags": ["eticheta 1", "eticheta 2"], "affected_currency": "EUR/RON", "market_sentiment": "UP", "market_impact_note": "o propozitie", "impact_strength": 2, "deep_dive": "Context: ...\\nDe ce conteaza: ...\\nCe urmeaza: ..."}]
Reguli:
- LIMBA: toate campurile text ("title", "summary", "bullets", "tags", "market_impact_note", "deep_dive") sunt scrise EXCLUSIV intr-o romana naturala si clara, cu diacritice (ă, â, î, ș, ț). Nu lasa propozitii sau titluri in engleza; numele proprii (persoane, organizatii, locuri) raman cum sunt, iar denumirile consacrate se folosesc in forma romaneasca (ex. "Rezerva Federala", "Uniunea Europeana").
- "title" este traducerea fidela a titlului original, sub 15 cuvinte, fara ghilimele si fara clickbait.
- "bullets" are 2-3 elemente, fiecare sub 20 de cuvinte, cu informatia esentiala; "tags" are 2-4 etichete scurte (1-2 cuvinte, litere mici) despre subiecte, persoane, locuri sau organizatii. Nu inventa informatii.
- "affected_currency" este UNA dintre perechile EUR/RON, USD/RON, BTC/USD, doar daca stirea poate influenta clar cursul acelei perechi; altfel null. "market_sentiment" este directia probabila a perechii: "UP" = prima moneda se apreciaza fata de a doua, "DOWN" = se depreciaza, "NEUTRAL" = impact neclar (obligatoriu "NEUTRAL" cand affected_currency este null). "market_impact_note" este o singura propozitie (sub 25 de cuvinte) care explica impactul, sau null cand affected_currency este null. "impact_strength" este 1 (impact minor), 2 (moderat) sau 3 (major: decizii de dobanda ale bancilor centrale, date macro surpriza, crize, reglementari majore pentru cripto); null cand affected_currency este null. Foloseste 3 rar, doar pentru stiri care pot misca vizibil cursul in cateva ore.
- "deep_dive" este o analiza completa in romana, de 150-220 de cuvinte, cu exact trei sectiuni, fiecare pe un rand nou, incepand cu titlul urmat de doua puncte: "Context:", "De ce conteaza:", "Ce urmeaza:". Explica stirea pentru un cititor roman (inclusiv relevanta pentru Romania sau UE, daca exista). Foloseste doar informatii din articol si cunostinte generale sigure; nu inventa cifre, nume sau citate; fara markdown.

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

  const title = String(parsed.title || '').replace(/^["„“]+|["”“]+$/g, '').trim().slice(0, 200);
  article.aiTitle = title || null;
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
    const strength = Number(parsed.impact_strength);
    article.marketImpactStrength = [1, 2, 3].includes(strength) ? strength : null;
  } else {
    // „Analizat, fără impact”: NEUTRAL și fără pereche.
    article.affectedCurrency = null;
    article.marketSentiment = 'NEUTRAL';
    article.marketImpactNote = null;
    article.marketImpactStrength = null;
  }
  article.aiProcessed = true;
  return true;
}

async function digestBatch(apiKey, batch) {
  const res = await fetchWithTimeout(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
    {
      method: 'POST',
      timeoutMs: REQUEST_TIMEOUT_MS,
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify({
        contents: [{ parts: [{ text: buildPrompt(batch) }] }],
        generationConfig: {
          temperature: 0.3,
          maxOutputTokens: 8192,
          responseMimeType: 'application/json',
          responseSchema: RESPONSE_SCHEMA,
          // Fără „gândire”: tokenii de raționament ar consuma din maxOutputTokens și ar tăia JSON-ul.
          thinkingConfig: { thinkingBudget: 0 }
        }
      })
    }
  );
  const data = await res.json();
  const blockReason = data?.promptFeedback?.blockReason;
  const finishReason = data?.candidates?.[0]?.finishReason;
  if (blockReason || finishReason === 'SAFETY') {
    const err = new Error(`blocat de filtrele de siguranță (${blockReason || finishReason})`);
    err.safety = true;
    throw err;
  }
  if (finishReason === 'MAX_TOKENS') throw new Error('răspuns tăiat (MAX_TOKENS)');
  const raw = (data?.candidates?.[0]?.content?.parts?.[0]?.text || '').replace(/^```(?:json)?|```$/g, '').trim();
  const parsed = JSON.parse(raw);
  const byId = new Map((Array.isArray(parsed) ? parsed : []).map((p) => [String(p?.id), p]));
  let ok = 0;
  for (const article of batch) {
    const p = byId.get(article.id);
    if (p && applyDigest(article, p)) ok++;
  }
  if (ok === 0) console.warn(`Gemini: niciun articol valid în răspuns (${byId.size} obiecte primite)`);
  return ok;
}

/**
 * Smart Digest pentru articole: rezumat + idei cheie + etichete + impact valutar + aprofundare.
 * Erorile lasă articolul neprocesat (`aiProcessed` rămâne false) → reîncercat la rularea următoare.
 *
 * @return `{ ok, attempted }` — `attempted` = articolele pentru care Gemini a dat un răspuns real
 * (valid sau nu). Cota epuizată (429), erorile 5xx, timeout-ul și bugetul de timp NU consumă
 * încercări: articolele respective nu apar în `attempted`.
 */
export async function digestArticles(apiKey, articles) {
  const started = Date.now();
  const attempted = new Set();
  let ok = 0;
  let calls = 0;

  const run = async (batch) => {
    if (calls++ > 0) await sleep(PAUSE_MS);
    ok += await digestBatch(apiKey, batch);
    batch.forEach((a) => attempted.add(a));
  };

  for (let i = 0; i < articles.length; i += BATCH_SIZE) {
    if (Date.now() - started > TOTAL_BUDGET_MS) {
      console.warn(`Gemini: buget de timp depășit, ${articles.length - i} articole rămân pentru rularea următoare`);
      break;
    }
    const batch = articles.slice(i, i + BATCH_SIZE);
    try {
      await run(batch);
    } catch (e) {
      if (isFatal(e)) {
        console.warn(`Gemini: oprit (${e.message}); restul articolelor rămân pentru rularea următoare`);
        break;
      }
      if (e.safety && batch.length > 1) {
        // Un singur articol sensibil (război, crimă) blochează tot lotul → le încercăm pe rând.
        console.warn(`Gemini: lot ${e.message}, reîncerc articol cu articol`);
        for (const article of batch) {
          try {
            await run([article]);
          } catch (e2) {
            if (isFatal(e2)) return { ok, attempted };
            if (e2.safety) attempted.add(article);
            console.warn(`Gemini: articol ${article.id} eșuat (${e2.message})`);
          }
        }
        continue;
      }
      if (e.name !== 'TimeoutError' && e.name !== 'AbortError') batch.forEach((a) => attempted.add(a));
      console.warn(`Gemini: lot eșuat (${e.message})`);
    }
  }
  return { ok, attempted };
}
