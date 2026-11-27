package ro.newsfilter.app.data.remote.localnews

import android.util.Xml
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.domain.model.NewsArticle
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Știri locale descărcate direct pe dispozitiv din Google News RSS (gratuit, fără cheie API,
 * fără citiri Firestore). Folosește doar API-urile Android incluse (`HttpURLConnection`,
 * `XmlPullParser`), deci nicio dependență nouă.
 *
 * Articolele primesc categoria `local` și un ID `local_<sha1(link)>`, ca să nu se suprapună
 * cu ID-urile din Firestore. Nu trec prin jobul `sync/`: rezumatul, ideile cheie și aprofundarea
 * se generează pe dispozitiv de [LocalNewsAiEnricher].
 */
@Singleton
class GoogleNewsRssSource @Inject constructor() {

    /** Descarcă și parsează știrile pentru „[city] [country]”. Aruncă [IOException] la erori de rețea. */
    suspend fun fetch(city: String, country: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val query = listOf(city, country).filter { it.isNotBlank() }.joinToString(" ")
        val url = URL(
            "https://news.google.com/rss/search?q=${URLEncoder.encode(query, "UTF-8")}&hl=ro&gl=RO&ceid=RO:ro"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NewsFilter")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Google News a răspuns cu codul ${connection.responseCode}")
            }
            connection.inputStream.use { parse(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(input: InputStream): List<NewsArticle> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val articles = mutableListOf<NewsArticle>()
        var fields: MutableMap<String, String>? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && articles.size < MAX_ITEMS) {
            when (event) {
                XmlPullParser.START_TAG -> when (val name = parser.name) {
                    "item" -> fields = mutableMapOf()
                    "title", "link", "pubDate", "source", "description" ->
                        fields?.put(name, parser.nextText().trim())
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    fields?.toArticleOrNull()?.let(articles::add)
                    fields = null
                }
            }
            event = parser.next()
        }
        return articles
    }

    private fun Map<String, String>.toArticleOrNull(): NewsArticle? {
        val link = this["link"]?.takeIf { it.isNotBlank() } ?: return null
        val source = this["source"].orEmpty()
        val rawTitle = this["title"]?.takeIf { it.isNotBlank() } ?: return null
        // Google News adaugă „ - Sursa” la finalul titlului.
        val title = if (source.isNotBlank()) rawTitle.removeSuffix(" - $source").trim() else rawTitle
        val description = this["description"]?.let {
            HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
        }.orEmpty()
        // Descrierea Google News e de obicei doar „titlu + sursă” → nu repetăm titlul pe card.
        val summary = description.takeIf { it.isNotBlank() && !it.startsWith(title) }
            ?: if (source.isNotBlank()) "Știre locală publicată de $source. $PLACEHOLDER_SUFFIX"
            else "Știre locală. $PLACEHOLDER_SUFFIX"
        return NewsArticle(
            id = "local_" + sha1(link).take(20),
            title = title,
            source = source,
            imageUrl = null,
            summary = summary,
            articleUrl = link,
            readTimeMinutes = 1,
            category = Category.LOCAL.slug,
            publishedAt = this["pubDate"]?.toIsoUtc().orEmpty()
        )
    }

    /** „Wed, 26 Nov 2026 10:00:00 GMT” → „2026-11-26T10:00:00Z” (sortabil lângă datele din Firestore). */
    private fun String.toIsoUtc(): String? = try {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString()
    } catch (_: Exception) {
        null
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val MAX_ITEMS = 30
        private const val PLACEHOLDER_SUFFIX = "Deschide articolul pentru textul complet."

        /** True pentru descrierea generică pusă când RSS-ul nu are text propriu. */
        fun isPlaceholderSummary(text: String): Boolean = text.endsWith(PLACEHOLDER_SUFFIX)
    }
}
