package ro.newsfilter.app.data.remote.localnews

import android.util.Base64
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Textul extras din pagina sursă a unei știri locale. */
data class ExtractedArticle(
    /** URL-ul final al articolului (pagina editorului), sau null dacă nu a putut fi aflat. */
    val resolvedUrl: String?,
    /** Paragrafele articolului, curățate și separate prin rând liber; null = indisponibil. */
    val text: String?,
    /** `og:description` / `description` din pagină. */
    val description: String?,
    /** `og:image` din pagină. */
    val imageUrl: String?
)

/**
 * Descarcă pagina editorului (linkurile Bing News sunt deja directe; pentru cele Google News
 * — `news.google.com/rss/articles/...` — încearcă să afle URL-ul real) și extrage
 * textul articolului din pagina editorului (paragrafele `<p>` + meta description), cu timeout scurt.
 * Doar API-uri Android incluse (fără dependențe noi). Orice eșec întoarce câmpuri null — apelantul
 * folosește atunci descrierea din RSS.
 *
 * Rezolvarea linkului: ID-urile vechi conțin URL-ul codat Base64; pentru restul urmăm redirecționările
 * HTTP. Dacă tot rămânem pe news.google.com (pagina intermediară JavaScript), linkul rămâne cel din RSS.
 */
@Singleton
class LocalArticleExtractor @Inject constructor() {

    suspend fun extract(googleLink: String): ExtractedArticle = withContext(Dispatchers.IO) {
        val target = decodeLegacyId(googleLink) ?: googleLink
        val page = runCatching { download(target) }.getOrNull()
        val finalUrl = page?.finalUrl ?: target.takeIf { !it.isGoogleNews() }
        val resolvedUrl = finalUrl?.takeIf { !it.isGoogleNews() }
        if (page == null || resolvedUrl == null) {
            return@withContext ExtractedArticle(resolvedUrl, null, null, null)
        }
        ExtractedArticle(
            resolvedUrl = resolvedUrl,
            text = extractParagraphs(page.html),
            description = metaContent(page.html, "og:description") ?: metaContent(page.html, "description"),
            imageUrl = metaContent(page.html, "og:image")?.takeIf { it.startsWith("http") }
        )
    }

    private class Page(val finalUrl: String, val html: String)

    /** GET cu redirecționări (inclusiv http ↔ https, pe care HttpURLConnection nu le urmează singur). */
    private fun download(startUrl: String): Page {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Language", "ro-RO,ro;q=0.9")
                // Fără cookie, Google redirecționează utilizatorii din UE spre pagina de consimțământ.
                if (url.isGoogleNews()) setRequestProperty("Cookie", "SOCS=CAI")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect fără Location")
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val type = connection.contentType.orEmpty()
                if (type.isNotEmpty() && !type.contains("html")) throw IOException("Nu este HTML: $type")
                val html = connection.inputStream.use { input ->
                    val bytes = input.readNBytesCompat(MAX_HTML_BYTES)
                    String(bytes, charsetOf(type))
                }
                return Page(url, html)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Prea multe redirecționări")
    }

    /** Paragrafele lungi din `<article>` (sau din toată pagina), fără meniuri / reclame scurte. */
    private fun extractParagraphs(html: String): String? {
        val cleaned = html
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|header|footer|aside|form|figure|figcaption|button)[^>]*>.*?</\\1>"), " ")
        val scope = Regex("(?is)<article[^>]*>(.*?)</article>").find(cleaned)?.groupValues?.get(1) ?: cleaned
        val paragraphs = Regex("(?is)<p[^>]*>(.*?)</p>").findAll(scope)
            .map { htmlToText(it.groupValues[1]) }
            .filter { it.length >= MIN_PARAGRAPH_CHARS && !it.looksLikeBoilerplate() }
            .distinct()
            .toList()
        if (paragraphs.isEmpty()) return null
        val text = buildString {
            for (p in paragraphs) {
                if (length + p.length > MAX_TEXT_CHARS) break
                if (isNotEmpty()) append("\n\n")
                append(p)
            }
        }
        return text.takeIf { it.split(' ').size >= MIN_ARTICLE_WORDS }
    }

    private fun metaContent(html: String, name: String): String? {
        val tag = Regex("(?is)<meta[^>]+(?:property|name)\\s*=\\s*[\"']${Regex.escape(name)}[\"'][^>]*>").find(html)?.value
            ?: return null
        val content = Regex("(?is)content\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: return null
        return htmlToText(content).takeIf { it.isNotBlank() }
    }

    private fun htmlToText(fragment: String): String =
        // Fără <img>: HtmlCompat le-ar înlocui cu caracterul „￼” (U+FFFC).
        HtmlCompat.fromHtml(fragment.replace(Regex("(?is)<img[^>]*>"), " "), HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace('￼', ' ').replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun String.looksLikeBoilerplate(): Boolean {
        val lower = lowercase()
        return BOILERPLATE.any { lower.contains(it) }
    }

    /**
     * Linkurile Google News mai vechi au ID-ul „CBMi…” = protobuf Base64 care conține direct URL-ul.
     * Cele noi (cu „AU_yqL…” în interior) nu se pot decoda local → null.
     */
    private fun decodeLegacyId(link: String): String? = runCatching {
        if (!link.isGoogleNews()) return null
        val id = link.substringAfter("/articles/", "").substringBefore('?').takeIf { it.isNotEmpty() } ?: return null
        val bytes = Base64.decode(id, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val raw = String(bytes, Charsets.ISO_8859_1)
        Regex("https?://[\\x21-\\x7E]+").find(raw)?.value?.takeIf { !it.contains("AU_yqL") }
    }.getOrNull()

    private fun String.isGoogleNews(): Boolean =
        runCatching { URL(this).host.endsWith("news.google.com") }.getOrDefault(true)

    private fun charsetOf(contentType: String) = runCatching {
        Regex("(?i)charset=([\\w-]+)").find(contentType)?.groupValues?.get(1)?.let { charset(it) }
    }.getOrNull() ?: Charsets.UTF_8

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 6_000
        const val MAX_REDIRECTS = 5
        const val MAX_HTML_BYTES = 1_500_000
        const val MAX_TEXT_CHARS = 12_000
        const val MIN_PARAGRAPH_CHARS = 60
        const val MIN_ARTICLE_WORDS = 60
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val BOILERPLATE = listOf(
            "cookie", "abonează", "aboneaza", "newsletter", "toate drepturile", "copyright",
            "javascript", "termeni și condiții", "politica de confidențialitate", "urmărește-ne",
            "disclaimer", "google discover", "google news", "sursă preferată", "actualizat pe", "actualizat la",
            "comentează", "foto:", "sursa foto", "citește și", "citeste si"
        )
    }
}
