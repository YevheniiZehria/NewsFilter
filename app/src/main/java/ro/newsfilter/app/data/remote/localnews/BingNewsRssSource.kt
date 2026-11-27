package ro.newsfilter.app.data.remote.localnews

import android.net.Uri
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
 * Sursa principală pentru știrile locale: Bing News RSS (public, gratuit, fără cheie API).
 * Spre deosebire de Google News, linkul Bing (`bing.com/news/apiclick.aspx?...&url=...`) conține
 * URL-ul real al articolului, deci [LocalArticleExtractor] poate descărca textul complet.
 *
 * Bing întoarce ~12 rezultate per cerere, așa că facem două cereri (cele mai noi + cele mai relevante)
 * și le unim fără duplicate. ID-ul `local_<sha1(url editor)>` rămâne stabil între sincronizări
 * (parametrii de tracking din linkul Bing se schimbă).
 */
@Singleton
class BingNewsRssSource @Inject constructor() {

    /** Aruncă [IOException] dacă ambele cereri eșuează. */
    suspend fun fetch(city: String, country: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(listOf(city, country).filter { it.isNotBlank() }.joinToString(" "), "UTF-8")
        val base = "https://www.bing.com/news/search?q=$query&format=rss&setlang=ro&cc=RO"
        val results = listOf("$base&qft=${URLEncoder.encode("sortbydate=\"1\"", "UTF-8")}", base)
            .map { url -> runCatching { download(url) } }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val kept = mutableListOf<NewsArticle>()
        results.flatMap { it.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            // Ordine stabilă (cea mai veche copie, apoi ID-ul): aceeași știre păstrează același ID
            // între sincronizări, deci nu se rezumă din nou.
            .sortedWith(compareBy<NewsArticle> { it.publishedAt }.thenBy { it.id })
            .forEach { article -> if (kept.none { it.isSameStoryAs(article) }) kept += article }
        kept.sortedByDescending { it.publishedAt }
    }

    /**
     * Același editor publică uneori știrea la două adrese (una redirecționează spre cealaltă), iar Bing
     * le întoarce pe amândouă cu titluri care diferă doar prin „firimituri” (ex. „Ştiri România” / „Politică”).
     * Le considerăm duplicate dacă vin de pe același site și au ≥ [SAME_STORY_OVERLAP] din cuvinte comune.
     */
    private fun NewsArticle.isSameStoryAs(other: NewsArticle): Boolean {
        if (host(articleUrl) != host(other.articleUrl)) return false
        val a = titleWords()
        val b = other.titleWords()
        if (a.isEmpty() || b.isEmpty()) return false
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size) >= SAME_STORY_OVERLAP
    }

    private fun NewsArticle.titleWords(): Set<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(title.lowercase()).map { it.value }.toSet()

    private fun host(url: String): String =
        Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")

    private fun download(url: String): List<NewsArticle> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Fără „Android” în User-Agent: altfel Bing servește site-ul mobil (HTML), nu RSS.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Bing News a răspuns cu codul ${connection.responseCode}")
            }
            if (connection.contentType?.contains("xml") != true) {
                throw IOException("Bing News nu a întors RSS (${connection.contentType})")
            }
            return connection.inputStream.use { parse(it) }
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
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (val name = parser.name) {
                    "item" -> fields = mutableMapOf()
                    "title", "link", "pubDate", "description", "News:Source", "News:Image" ->
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
        // Linkul real al editorului; dacă lipsește parametrul, păstrăm linkul Bing (redirecționează).
        val articleUrl = runCatching { Uri.parse(link).getQueryParameter("url") }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: link
        val title = this["title"]?.let(::clean)?.takeIf { it.isNotBlank() } ?: return null
        val source = this["News:Source"].orEmpty().ifBlank { Uri.parse(articleUrl).host.orEmpty().removePrefix("www.") }
        val description = this["description"]?.let(::clean).orEmpty()
        return NewsArticle(
            id = "local_" + sha1(canonical(articleUrl)).take(20),
            title = title,
            source = source,
            imageUrl = this["News:Image"]?.takeIf { it.startsWith("http") }?.replaceFirst("http://", "https://"),
            summary = description.ifBlank { title },
            articleUrl = articleUrl,
            readTimeMinutes = 1,
            category = Category.LOCAL.slug,
            publishedAt = this["pubDate"]?.toIsoUtc().orEmpty()
        )
    }

    /**
     * Același articol apare uneori în ambele cereri cu parametri de tracking diferiți:
     * ID-ul se calculează din gazdă (fără „www.”) + cale, fără query / fragment.
     */
    private fun canonical(url: String): String = runCatching {
        val uri = Uri.parse(url)
        uri.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.") + uri.path.orEmpty().trimEnd('/')
    }.getOrDefault(url)

    private fun clean(text: String): String =
        HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun String.toIsoUtc(): String? = try {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString()
    } catch (_: Exception) {
        null
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val SAME_STORY_OVERLAP = 0.75
        const val USER_AGENT = "Mozilla/5.0 (compatible; NewsFilter RSS reader)"
    }
}
