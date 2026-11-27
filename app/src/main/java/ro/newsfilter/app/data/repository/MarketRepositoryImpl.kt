package ro.newsfilter.app.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import ro.newsfilter.app.core.util.NetworkMonitor
import ro.newsfilter.app.data.local.dao.NewsDao
import ro.newsfilter.app.data.local.entity.NewsEntity
import ro.newsfilter.app.data.remote.firestore.FirestorePaths
import ro.newsfilter.app.data.remote.firestore.toMarketPoints
import ro.newsfilter.app.data.remote.firestore.toMarketRates
import ro.newsfilter.app.domain.model.CommunitySentiment
import ro.newsfilter.app.domain.model.MarketEvent
import ro.newsfilter.app.domain.model.MarketHistory
import ro.newsfilter.app.domain.model.MarketHistoryEvent
import ro.newsfilter.app.domain.model.MarketPairs
import ro.newsfilter.app.domain.model.MarketPoint
import ro.newsfilter.app.domain.model.MarketPulse
import ro.newsfilter.app.domain.model.MarketRange
import ro.newsfilter.app.domain.model.MarketRate
import ro.newsfilter.app.domain.model.MarketSentiment
import ro.newsfilter.app.domain.repository.MarketRepository
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementarea [MarketRepository] peste Cloud Firestore + cache-ul local de articole.
 *
 * - Cursurile: listener în timp real pe `market/pulse` (o citire la pornire, apoi doar la
 *   modificări — jobul de sincronizare le actualizează la 30 de minute).
 * - Istoricul: un singur document per pereche și rezoluție (`{PAIR}_intraday` pentru
 *   1 Z / 5 Z / 1 L, `{PAIR}_daily` pentru 1 A / Max), păstrat în memorie 30 de minute; seria
 *   pentru fiecare interval se calculează local, pe aceleași „găleți” ca vechiul API
 *   (max. ~260 de puncte), deci comutarea între intervale nu costă citiri.
 * - Știrile cu impact (bannerul și punctele de pe grafic) vin din Room, fără citiri suplimentare.
 */
@Singleton
class MarketRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val newsDao: NewsDao,
    private val networkMonitor: NetworkMonitor
) : MarketRepository {

    private data class CachedPoints(val points: List<MarketPoint>, val fetchedAt: Long)

    /** Punctele brute per document (`USD_RON_intraday`, …). */
    private val pointsCache = ConcurrentHashMap<String, CachedPoints>()

    /** Ultima serie calculată per (pereche, interval) — afișată instant / offline. */
    private val historyCache = ConcurrentHashMap<String, MarketHistory>()

    override fun observeMarketPulse(): Flow<MarketPulse> =
        combine(
            observeRates(),
            newsDao.observeLatestMarketEvent(),
            newsDao.observeCommunitySentiment(Instant.now().minus(SENTIMENT_WINDOW).toString())
        ) { rates, latest, counts ->
            MarketPulse(
                rates = rates,
                latestEvent = latest?.toMarketEventOrNull(),
                communitySentiment = counts.groupBy { it.pair }.mapValues { (_, rows) ->
                    CommunitySentiment(
                        bullish = rows.filter { it.sentiment == MarketSentiment.UP.name }.sumOf { it.count },
                        bearish = rows.filter { it.sentiment == MarketSentiment.DOWN.name }.sumOf { it.count }
                    )
                }
            )
        }

    private fun observeRates(): Flow<List<MarketRate>> = callbackFlow {
        val registration = firestore.document(FirestorePaths.MARKET_PULSE)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null -> trySend(snapshot.toMarketRates())
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun fetchMarketHistory(pair: String, range: MarketRange): MarketHistory {
        val spec = RangeSpec.of(range)
        val now = System.currentTimeMillis()
        val docId = "${FirestorePaths.pairKey(pair)}_${spec.resolution}"

        val points = pointsCache[docId]?.takeIf { now - it.fetchedAt < POINTS_TTL_MS }?.points
            ?: run {
                ensureOnline()
                val doc = firestore.collection(FirestorePaths.MARKET_HISTORY).document(docId).get().await()
                doc.toMarketPoints().also { pointsCache[docId] = CachedPoints(it, now) }
            }

        val since = now - spec.spanMs
        val history = MarketHistory(
            pair = pair,
            range = range,
            points = bucket(points.filter { it.timestampMillis > since }, spec.bucketMs),
            events = newsDao.getMarketEvents(pair, MAX_EVENT_CANDIDATES)
                .mapNotNull { it.toHistoryEventOrNull() }
                .filter { it.timestampMillis > since }
                .take(MAX_EVENTS)
                .sortedBy { it.timestampMillis }
        )
        historyCache[cacheKey(pair, range)] = history
        return history
    }

    override fun cachedMarketHistory(pair: String, range: MarketRange): MarketHistory? =
        historyCache[cacheKey(pair, range)]

    // ------------------------------------------------------------------

    /** Media punctelor din fiecare „găleată” (echivalentul `date_bin` din vechiul API). */
    private fun bucket(points: List<MarketPoint>, bucketMs: Long): List<MarketPoint> =
        points.groupBy { (it.timestampMillis - BUCKET_ORIGIN_MS).floorDiv(bucketMs) }
            .map { (index, group) -> MarketPoint(BUCKET_ORIGIN_MS + index * bucketMs, group.map { it.rate }.average()) }
            .sortedBy { it.timestampMillis }

    /** Bannerul Market Pulse: doar știrile din ultimele 48 h. */
    private fun NewsEntity.toMarketEventOrNull(): MarketEvent? {
        val pair = MarketPairs.normalize(affectedCurrency) ?: return null
        val time = publishedAt.toEpochMillisOrNull() ?: return null
        if (System.currentTimeMillis() - time > LATEST_EVENT_WINDOW_MS) return null
        return MarketEvent(
            articleId = id,
            title = title,
            articleUrl = articleUrl,
            pair = pair,
            sentiment = MarketSentiment.fromRaw(marketSentiment) ?: MarketSentiment.NEUTRAL,
            note = marketImpactNote?.trim()?.takeIf { it.isNotEmpty() },
            publishedAt = publishedAt
        )
    }

    private fun NewsEntity.toHistoryEventOrNull(): MarketHistoryEvent? {
        val sentiment = MarketSentiment.fromRaw(marketSentiment)?.takeIf { it != MarketSentiment.NEUTRAL } ?: return null
        return MarketHistoryEvent(
            articleId = id,
            title = title,
            articleUrl = articleUrl,
            sentiment = sentiment,
            note = marketImpactNote?.trim()?.takeIf { it.isNotEmpty() },
            timestampMillis = publishedAt.toEpochMillisOrNull() ?: return null
        )
    }

    private fun String.toEpochMillisOrNull(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

    private fun cacheKey(pair: String, range: MarketRange) = "$pair|${range.apiValue}"

    private fun ensureOnline() {
        if (!networkMonitor.isCurrentlyOnline()) throw IOException("Dispozitivul este offline")
    }

    /** Documentul sursă, durata și mărimea găleții pentru fiecare interval al graficului. */
    private enum class RangeSpec(val resolution: String, val spanMs: Long, val bucketMs: Long) {
        D1("intraday", 24 * HOUR_MS, 10 * MINUTE_MS),
        D5("intraday", 5 * DAY_MS, 30 * MINUTE_MS),
        M1("intraday", 30 * DAY_MS, 4 * HOUR_MS),
        Y1("daily", 365 * DAY_MS, DAY_MS),
        MAX("daily", 5 * 365 * DAY_MS, 7 * DAY_MS);

        companion object {
            fun of(range: MarketRange) = when (range) {
                MarketRange.D1 -> D1
                MarketRange.D5 -> D5
                MarketRange.M1 -> M1
                MarketRange.Y1 -> Y1
                MarketRange.MAX -> MAX
            }
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
        const val POINTS_TTL_MS = 30 * MINUTE_MS
        const val LATEST_EVENT_WINDOW_MS = 48 * HOUR_MS
        const val MAX_EVENTS = 30
        const val MAX_EVENT_CANDIDATES = 200

        /** Fereastra pentru sentimentul comunității (articolele trăiesc oricum max. 14 zile). */
        val SENTIMENT_WINDOW: Duration = Duration.ofDays(7)

        /** Luni, 2000-01-03 00:00 UTC — originea găleților (ca în vechiul API: săptămânile încep lunea). */
        const val BUCKET_ORIGIN_MS = 946_857_600_000L
    }
}
