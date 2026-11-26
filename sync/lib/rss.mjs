import { XMLParser } from 'fast-xml-parser';
import { USER_AGENT, articleId, fetchWithTimeout } from './util.mjs';

/** Feed-urile BBC și categoria (slug-ul din `Category.slug` din aplicație) fiecăruia. */
export const FEEDS = [
  { category: 'tech', url: 'https://feeds.bbci.co.uk/news/technology/rss.xml' },
  { category: 'economy', url: 'https://feeds.bbci.co.uk/news/business/rss.xml' },
  { category: 'sport', url: 'https://feeds.bbci.co.uk/sport/rss.xml' },
  { category: 'science', url: 'https://feeds.bbci.co.uk/news/science_and_environment/rss.xml' },
  { category: 'health', url: 'https://feeds.bbci.co.uk/news/health/rss.xml' },
  { category: 'world', url: 'https://feeds.bbci.co.uk/news/world/rss.xml' }
];

const BBC_IMAGE_PLACEHOLDER = 'https://news.bbcimg.co.uk/nol/shared/img/bbc_news_120x60.gif';

const parser = new XMLParser({ ignoreAttributes: false, attributeNamePrefix: '@_' });

const text = (v) => (typeof v === 'object' && v !== null ? v['#text'] ?? '' : v ?? '').toString();
const stripTags = (s) => s.replace(/<[^>]+>/g, '').trim();

/** Estimare timp de citire (200 de cuvinte / minut, minim 1). */
export function estimateReadTime(content) {
  if (!content) return 2;
  return Math.max(1, Math.round(content.trim().split(/\s+/).length / 200));
}

/**
 * Descarcă toate feed-urile și le normalizează în articole (fără duplicate după URL).
 * Un feed care eșuează este ignorat (celelalte continuă).
 */
export async function fetchAllFeeds() {
  const seen = new Set();
  const articles = [];

  const results = await Promise.allSettled(FEEDS.map(async (feed) => {
    const res = await fetchWithTimeout(feed.url, { headers: { 'User-Agent': USER_AGENT } });
    const xml = parser.parse(await res.text());
    const items = xml?.rss?.channel?.item ?? [];
    return { feed, items: Array.isArray(items) ? items : [items] };
  }));

  for (const r of results) {
    if (r.status === 'rejected') {
      console.warn(`RSS indisponibil: ${r.reason?.message ?? r.reason}`);
      continue;
    }
    for (const item of r.value.items) {
      const url = text(item.link || item.guid).trim();
      if (!url || seen.has(url)) continue;
      seen.add(url);

      const description = stripTags(text(item.description));
      const pubDate = text(item.pubDate);
      const published = pubDate ? new Date(pubDate) : new Date();
      const image = item['media:thumbnail']?.['@_url'] || item.enclosure?.['@_url'] || null;

      articles.push({
        id: articleId(url),
        title: stripTags(text(item.title)),
        source: 'BBC News',
        imageUrl: image && image.startsWith('http') ? image : BBC_IMAGE_PLACEHOLDER,
        summary: description.slice(0, 500),
        articleUrl: url,
        readTimeMinutes: estimateReadTime(description),
        category: r.value.feed.category,
        publishedAt: (Number.isNaN(published.getTime()) ? new Date() : published).toISOString(),
        fullContent: null
      });
    }
  }
  return articles.filter((a) => a.title);
}
