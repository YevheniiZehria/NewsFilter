import { createHash } from 'node:crypto';

export const DAY_MS = 86_400_000;
export const USER_AGENT = 'Mozilla/5.0 (compatible; NewsFilterBot/3.0)';

/** ID stabil de document pentru un articol: primele 20 de caractere din SHA-1(URL). */
export const articleId = (url) => createHash('sha1').update(url).digest('hex').slice(0, 20);

/** Cheia de document pentru o pereche valutară („USD/RON” → „USD_RON”; „/” nu e permis în ID-uri). */
export const pairKey = (pair) => pair.replace('/', '_');

export const round = (v, d) => Number(Number(v).toFixed(d));

/** fetch cu timeout; aruncă eroare (cu `status`) la status HTTP ≠ 2xx. */
export async function fetchWithTimeout(url, { timeoutMs = 20_000, ...init } = {}) {
  const res = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status} pentru ${url.split('?')[0]}`);
    err.status = res.status;
    throw err;
  }
  return res;
}

/**
 * URL canonic pentru deduplicare: fără query (parametri de tracking `at_*`) și fragment,
 * același host pentru bbc.co.uk / bbc.com.
 */
export function canonicalUrl(raw) {
  try {
    const u = new URL(raw);
    u.search = '';
    u.hash = '';
    u.hostname = u.hostname.replace(/^www\.bbc\.co\.uk$/, 'www.bbc.com').replace(/^bbc\.co\.uk$/, 'www.bbc.com');
    return u.toString();
  } catch {
    return raw;
  }
}

export async function getJson(url, timeoutMs) {
  const res = await fetchWithTimeout(url, {
    timeoutMs,
    headers: { Accept: 'application/json', 'User-Agent': USER_AGENT }
  });
  return res.json();
}

/** Rulează `fn` pe elemente în loturi de câte `concurrency`. */
export async function inBatches(items, concurrency, fn) {
  for (let i = 0; i < items.length; i += concurrency) {
    await Promise.all(items.slice(i, i + concurrency).map(fn));
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
