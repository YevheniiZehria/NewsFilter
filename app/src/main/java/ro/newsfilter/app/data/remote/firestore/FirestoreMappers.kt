package ro.newsfilter.app.data.remote.firestore

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import ro.newsfilter.app.domain.model.MarketPairs
import ro.newsfilter.app.domain.model.MarketPoint
import ro.newsfilter.app.domain.model.MarketRate
import ro.newsfilter.app.domain.model.MarketSentiment
import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Structura Cloud Firestore (scrisă de jobul `sync/` din GitHub Actions și de aplicație):
 *
 * ```
 * articles/{id}                        știrile procesate (aplicația scrie doar upvotes / downvotes)
 * market/pulse                         cursurile curente: rates.{EUR_RON|USD_RON|BTC_USD}
 * market_history/{PAIR}_intraday       puncte la 30 min, ultimele 30 de zile (1 Z / 5 Z / 1 L)
 * market_history/{PAIR}_daily          puncte zilnice, max. 5 ani (1 A / Max)
 * users/{uid}                          profilul
 * users/{uid}/bookmarks/{articleId}    favorite (copie a articolului)
 * users/{uid}/votes/{articleId}        voturile 👍 / 👎
 * ```
 * Câmpurile documentelor sunt opționale la citire: un document incomplet este ignorat
 * ([toNewsArticleOrNull] întoarce null), fără să strice restul listei.
 */
object FirestorePaths {
    const val ARTICLES = "articles"
    const val USERS = "users"
    const val BOOKMARKS = "bookmarks"
    const val VOTES = "votes"
    const val MARKET_PULSE = "market/pulse"
    const val MARKET_HISTORY = "market_history"

    /** „USD/RON” → „USD_RON” („/” nu este permis în ID-urile de document). */
    fun pairKey(pair: String) = pair.replace('/', '_')
}

private fun DocumentSnapshot.stringList(field: String): List<String> =
    (get(field) as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }

/** Document `articles/{id}` sau `users/{uid}/bookmarks/{id}` → model de domeniu. */
fun DocumentSnapshot.toNewsArticleOrNull(isBookmarked: Boolean = false): NewsArticle? {
    val title = getString("title")?.takeIf { it.isNotBlank() } ?: return null
    val articleUrl = getString("articleUrl")?.takeIf { it.isNotBlank() } ?: return null
    return NewsArticle(
        id = id,
        title = title,
        source = getString("source").orEmpty(),
        imageUrl = getString("imageUrl"),
        summary = getString("summary").orEmpty(),
        articleUrl = articleUrl,
        readTimeMinutes = (getLong("readTimeMinutes") ?: 1L).toInt().coerceAtLeast(1),
        category = getString("category").orEmpty().lowercase(),
        publishedAt = getString("publishedAt").orEmpty(),
        isBookmarked = isBookmarked,
        fullContent = getString("fullContent"),
        aiTitle = getString("aiTitle")?.trim()?.takeIf { it.isNotEmpty() },
        aiSummary = getString("aiSummary"),
        aiBullets = stringList("aiBullets"),
        aiTags = stringList("aiTags"),
        deepDive = getString("deepDive")?.takeIf { it.isNotBlank() },
        affectedCurrency = MarketPairs.normalize(getString("affectedCurrency")),
        marketSentiment = MarketSentiment.fromRaw(getString("marketSentiment")),
        marketImpactNote = getString("marketImpactNote")?.trim()?.takeIf { it.isNotEmpty() },
        upvotes = (getLong("upvotes") ?: 0L).toInt().coerceAtLeast(0),
        downvotes = (getLong("downvotes") ?: 0L).toInt().coerceAtLeast(0)
    )
}

/** Copia articolului salvată în `users/{uid}/bookmarks/{id}` (supraviețuiește ștergerii știrii). */
fun NewsArticle.toBookmarkData(): Map<String, Any?> = mapOf(
    "title" to title,
    "source" to source,
    "imageUrl" to imageUrl,
    "summary" to summary,
    "articleUrl" to articleUrl,
    "readTimeMinutes" to readTimeMinutes,
    "category" to category,
    "publishedAt" to publishedAt,
    "fullContent" to fullContent,
    "aiTitle" to aiTitle,
    "aiSummary" to aiSummary,
    "aiBullets" to aiBullets,
    "aiTags" to aiTags,
    "deepDive" to deepDive,
    "affectedCurrency" to affectedCurrency,
    "marketSentiment" to marketSentiment?.name,
    "marketImpactNote" to marketImpactNote,
    "createdAt" to FieldValue.serverTimestamp()
)

/** `market/pulse` → cursurile cunoscute, în ordinea din [MarketPairs.all]. */
fun DocumentSnapshot.toMarketRates(): List<MarketRate> {
    val rates = get("rates") as? Map<*, *> ?: return emptyList()
    val byPair = rates.values.mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        val pair = MarketPairs.normalize(m["pair"] as? String) ?: return@mapNotNull null
        val rate = (m["rate"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return@mapNotNull null
        MarketRate(
            pair = pair,
            rate = rate,
            changePercent = (m["changePercent"] as? Number)?.toDouble(),
            updatedAt = m["updatedAt"] as? String ?: "",
            source = m["source"] as? String ?: ""
        )
    }.associateBy { it.pair }
    return MarketPairs.all.mapNotNull { byPair[it] }
}

/** `market_history/{PAIR}_intraday|daily` → punctele seriei (`points: [{t: ms, v: curs}]`), sortate cronologic. */
fun DocumentSnapshot.toMarketPoints(): List<MarketPoint> =
    (get("points") as? List<*>).orEmpty().mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        val t = (m["t"] as? Number)?.toLong() ?: return@mapNotNull null
        val v = (m["v"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: return@mapNotNull null
        MarketPoint(t, v)
    }.sortedBy { it.timestampMillis }
