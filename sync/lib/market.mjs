import { DAY_MS, getJson, round } from './util.mjs';

const LONG_TTL_MS = DAY_MS;
const pct = (cur, prev) => (prev ? round(((cur - prev) / prev) * 100, 4) : null);

/**
 * Cursuri curente + (o dată pe zi) istoricul zilnic lung.
 *  - EUR/RON, USD/RON: BCE prin Frankfurter (cursul se schimbă o dată pe zi lucrătoare);
 *    istoric 5 ani, punctul zilei la 14:00 UTC (fixarea BCE ~16:00 CET).
 *  - BTC/USD: CoinGecko; istoric 365 de zile (limita planului gratuit).
 * O sursă care eșuează nu emite nimic; cursul ei rămâne cel salvat anterior.
 *
 * @param state `meta/sync` (fiatLongFetchedAt, btcLongFetchedAt), actualizat pe loc
 * @returns [{ pair, rate, changePercent, source, history?: [{t, v}] }]
 */
export async function fetchMarketRates(state, now = Date.now()) {
  const out = [];
  const due = (key) => !state[key] || now - state[key] > LONG_TTL_MS;

  // 1. Fiat (BCE)
  const longFiat = due('fiatLongFetchedAt');
  try {
    const start = new Date(now - (longFiat ? 5 * 366 : 7) * DAY_MS).toISOString().slice(0, 10);
    const data = await getJson(`https://api.frankfurter.dev/v1/${start}..?base=EUR&symbols=RON,USD`);
    const days = Object.keys(data.rates || {}).sort();
    const last = days.length ? data.rates[days.at(-1)] : null;
    const prev = days.length > 1 ? data.rates[days.at(-2)] : null;
    const history = (fn) => (longFiat
      ? days.map((d) => ({ t: Date.parse(`${d}T14:00:00Z`), v: fn(data.rates[d]) }))
        .filter((h) => Number.isFinite(h.v) && h.v > 0)
        .map((h) => ({ ...h, v: round(h.v, 6) }))
      : undefined);

    if (last?.RON) {
      out.push({
        pair: 'EUR/RON', rate: round(last.RON, 6), changePercent: pct(last.RON, prev?.RON),
        source: 'BCE (Frankfurter)', history: history((r) => r.RON)
      });
      if (last.USD) {
        const usdRon = last.RON / last.USD;
        const prevUsdRon = prev?.RON && prev?.USD ? prev.RON / prev.USD : null;
        out.push({
          pair: 'USD/RON', rate: round(usdRon, 6), changePercent: pct(usdRon, prevUsdRon),
          source: 'BCE (Frankfurter)', history: history((r) => r.RON / r.USD)
        });
      }
      if (longFiat) state.fiatLongFetchedAt = now;
    }
  } catch (e) {
    console.warn(`Frankfurter indisponibil: ${e.message}`);
  }

  // 2. Crypto (CoinGecko)
  try {
    const data = await getJson('https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd&include_24hr_change=true');
    const btc = data?.bitcoin;
    if (typeof btc?.usd === 'number') {
      const item = {
        pair: 'BTC/USD',
        rate: round(btc.usd, 2),
        changePercent: typeof btc.usd_24h_change === 'number' ? round(btc.usd_24h_change, 4) : null,
        source: 'CoinGecko'
      };
      if (due('btcLongFetchedAt')) {
        try {
          const chart = await getJson('https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=365&interval=daily');
          const byDay = new Map();
          for (const [ms, price] of chart?.prices || []) {
            if (Number.isFinite(price) && price > 0) byDay.set(Math.floor(ms / DAY_MS) * DAY_MS, round(price, 2));
          }
          item.history = [...byDay.entries()].map(([t, v]) => ({ t, v }));
          if (item.history.length) state.btcLongFetchedAt = now;
        } catch (e) {
          console.warn(`CoinGecko istoric indisponibil: ${e.message}`);
        }
      }
      out.push(item);
    }
  } catch (e) {
    console.warn(`CoinGecko indisponibil: ${e.message}`);
  }

  return out;
}

/** Adaugă un punct (rotunjit la 30 min) și păstrează doar ultimele `keepMs`. */
export function appendPoint(points, t, v, keepMs) {
  const slot = Math.floor(t / (30 * 60_000)) * 30 * 60_000;
  const list = (points || []).filter((p) => p.t !== slot && p.t > t - keepMs);
  list.push({ t: slot, v });
  return list.sort((a, b) => a.t - b.t);
}

/** Unește punctele zilnice existente cu cele noi (cele noi câștigă) și păstrează ultimele `keepMs`. */
export function mergeDaily(existing, incoming, now, keepMs) {
  const byT = new Map((existing || []).map((p) => [p.t, p.v]));
  for (const p of incoming) byT.set(p.t, p.v);
  return [...byT.entries()]
    .filter(([t]) => t > now - keepMs)
    .sort((a, b) => a[0] - b[0])
    .map(([t, v]) => ({ t, v }));
}
