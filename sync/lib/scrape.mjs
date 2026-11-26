import { USER_AGENT, fetchWithTimeout, inBatches } from './util.mjs';
import { estimateReadTime } from './rss.mjs';

const CONCURRENCY = 5;
const TIMEOUT_MS = 10_000;

const stripHtml = (s) => s
  .replace(/<script[\s\S]*?<\/script>/gi, '')
  .replace(/<style[\s\S]*?<\/style>/gi, '')
  .replace(/<[^>]+>/g, ' ')
  .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
  .replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/&#39;|&apos;/g, "'")
  .replace(/\s+/g, ' ').trim();

/** Extrage textul articolului dintr-o pagină BBC (paragrafele din <article>), max. 15.000 caractere. */
export function extractBBCContent(html) {
  const articleMatch = html.match(/<article[\s\S]*?<\/article>/i);
  const scope = articleMatch ? articleMatch[0] : html;
  const blocks = [];
  for (const p of scope.match(/<p\b[^>]*>[\s\S]*?<\/p>/gi) || []) {
    const t = stripHtml(p);
    if (t.length > 40) blocks.push(t);
  }
  if (blocks.length === 0) {
    for (const d of scope.match(/<div[^>]*data-component="text-block"[^>]*>[\s\S]*?<\/div>/gi) || []) {
      const t = stripHtml(d);
      if (t.length > 40) blocks.push(t);
    }
  }
  return blocks.length ? blocks.join('\n\n').slice(0, 15_000) : null;
}

/**
 * Completează `fullContent` (și timpul de citire, calculat acum din textul complet) pentru articole.
 * Erorile (timeout, pagină blocată) sunt ignorate: articolul rămâne cu rezumatul RSS.
 */
export async function scrapeArticles(articles) {
  await inBatches(articles, CONCURRENCY, async (article) => {
    try {
      const res = await fetchWithTimeout(article.articleUrl, {
        timeoutMs: TIMEOUT_MS,
        headers: { 'User-Agent': USER_AGENT, Accept: 'text/html,application/xhtml+xml' }
      });
      const content = extractBBCContent(await res.text());
      if (content) {
        article.fullContent = content;
        article.readTimeMinutes = estimateReadTime(content);
      }
    } catch {
      // continuăm fără textul complet
    }
  });
}
