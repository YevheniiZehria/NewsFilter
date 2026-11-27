import { round } from './util.mjs';

/** Topicul FCM la care se abonează aplicația (Setări → „Alerte de piață”). */
export const ALERT_TOPIC = 'market_alerts';

/** Pragul pentru o mișcare bruscă față de rularea anterioară (%); suprascris de ALERT_THRESHOLD_PCT. */
const RATE_THRESHOLD_PCT = Number(process.env.ALERT_THRESHOLD_PCT) || 1.5;

const fmtRate = (pair, v) => (pair === 'BTC/USD'
  ? `${Math.round(v).toLocaleString('ro-RO')} USD`
  : `${v.toFixed(4).replace('.', ',')} RON`);
const fmtPct = (p) => `${p > 0 ? '+' : ''}${p.toFixed(1).replace('.', ',')}%`;

/**
 * Alertele de curs: perechile care s-au mișcat cu ≥ prag față de cursul anterior.
 * @param moves [{ pair, rate, previous }] — `previous` = ultimul punct intraday (deja citit, 0 citiri în plus)
 */
export function rateAlerts(moves) {
  return moves
    .filter((m) => m.previous > 0 && m.rate > 0)
    .map((m) => ({ ...m, pct: round(((m.rate - m.previous) / m.previous) * 100, 2) }))
    .filter((m) => Math.abs(m.pct) >= RATE_THRESHOLD_PCT)
    .map((m) => ({
      title: `${m.pct > 0 ? '📈' : '📉'} ${m.pair} a ${m.pct > 0 ? 'crescut' : 'scăzut'} cu ${fmtPct(m.pct)}`,
      body: `Curs nou: ${fmtRate(m.pair, m.rate)} — vezi impactul în NewsFilter`,
      data: { type: 'rate', pair: m.pair }
    }));
}

/**
 * Alerta de știre cu impact mare (max. 1 / rulare): primul articol procesat acum de Gemini
 * cu pereche valutară, direcție UP/DOWN și `marketImpactStrength` 3.
 */
export function newsAlert(articles) {
  const a = articles.find((x) => x.aiProcessed && x.affectedCurrency
    && (x.marketSentiment === 'UP' || x.marketSentiment === 'DOWN') && x.marketImpactStrength === 3);
  if (!a) return null;
  const currency = a.affectedCurrency.split('/')[0];
  const minutes = Math.min(Math.max(a.readTimeMinutes || 1, 1), 5);
  return {
    title: `⚡ Impact ${currency}: ${a.marketSentiment === 'UP' ? 'presiune de creștere' : 'presiune de scădere'}`,
    body: `${a.aiTitle || a.title} — rezumat în ${minutes} min`,
    data: { type: 'news', articleId: a.id, pair: a.affectedCurrency }
  };
}

/**
 * Trimite alertele pe topic. Mesaje „data-only” cu prioritate mare: aplicația construiește ea
 * notificarea (NewsFilterMessagingService), deci atingerea deschide direct ecranul potrivit.
 * FCM este gratuit și nu consumă citiri / scrieri Firestore.
 */
export async function sendAlerts(alerts, { dryRun, messaging }) {
  for (const alert of alerts) {
    const message = {
      topic: ALERT_TOPIC,
      data: { ...alert.data, title: alert.title, body: alert.body },
      android: { priority: 'high', ttl: 3 * 3600 * 1000 }
    };
    if (dryRun || !messaging) {
      console.log(`  [FCM dry-run] ${JSON.stringify(message)}`);
      continue;
    }
    try {
      const id = await messaging.send(message);
      console.log(`  FCM trimis: ${alert.title} (${id})`);
    } catch (e) {
      console.warn(`  FCM eșuat (${alert.title}): ${e.message}`);
    }
  }
}
