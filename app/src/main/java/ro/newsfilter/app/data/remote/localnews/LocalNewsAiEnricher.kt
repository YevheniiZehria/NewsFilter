package ro.newsfilter.app.data.remote.localnews

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ro.newsfilter.app.BuildConfig
import ro.newsfilter.app.domain.model.NewsArticle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Smart Digest pentru știrile locale, generat pe dispozitiv (știrile locale nu trec prin jobul `sync/`):
 * 1. [LocalArticleExtractor] află URL-ul real și extrage textul articolului;
 * 2. Gemini (REST, cheia `GEMINI_API_KEY` din `local.properties` → `BuildConfig`) scrie rezumatul,
 *    ideile cheie, etichetele și aprofundarea, câte [AI_BATCH_SIZE] articole per cerere;
 * 3. fără cheie, offline sau la orice eroare Gemini → rezumat extractiv local (primele propoziții,
 *    etichete după frecvența cuvintelor). Astfel niciun articol nu rămâne gol.
 *
 * Rezultatul se salvează în Room de apelant, deci fiecare articol se procesează o singură dată.
 */
@Singleton
class LocalNewsAiEnricher @Inject constructor(
    private val extractor: LocalArticleExtractor
) {

    /** Îmbogățește [articles] (apelantul limitează numărul, ca să rămânem în cota gratuită). */
    suspend fun enrich(articles: List<NewsArticle>, city: String): List<NewsArticle> = coroutineScope {
        if (articles.isEmpty()) return@coroutineScope emptyList()
        val permits = Semaphore(EXTRACT_CONCURRENCY)
        val withText = articles.map { article ->
            async {
                permits.withPermit {
                    val extracted = try {
                        extractor.extract(article.articleUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ExtractedArticle(null, null, null, null)
                    }
                    article.withExtracted(extracted)
                }
            }
        }.awaitAll()

        val digests = if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            emptyMap()
        } else {
            withText.chunked(AI_BATCH_SIZE).flatMap { batch ->
                try {
                    requestGemini(batch).entries
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini indisponibil pentru știrile locale, folosesc rezumatul extractiv: ${e.message}")
                    emptySet()
                }
            }.associate { it.key to it.value }
        }

        withText.map { article -> digests[article.id]?.let { article.applyDigest(it) } ?: article.extractiveDigest(city) }
    }

    // ------------------------------------------------------------------ extragere

    /** Textul complet = paragrafele paginii; altfel meta description / descrierea RSS (fără „articol complet”). */
    private fun NewsArticle.withExtracted(e: ExtractedArticle): NewsArticle {
        val body = e.text
        return copy(
            articleUrl = e.resolvedUrl ?: articleUrl,
            // Imaginea editorului (rezoluție mare) are prioritate față de miniatura Bing.
            imageUrl = e.imageUrl ?: imageUrl,
            fullContent = body,
            summary = e.description?.takeIf { it.length > summary.length && !it.startsWith(title) } ?: summary,
            readTimeMinutes = readTime(body ?: e.description ?: summary)
        )
    }

    /** Textul pe care lucrează AI-ul / rezumatul extractiv (fără textul generic pus de [GoogleNewsRssSource]). */
    private fun NewsArticle.sourceText(): String =
        fullContent ?: summary.takeUnless { GoogleNewsRssSource.isPlaceholderSummary(it) } ?: title

    // ------------------------------------------------------------------ Gemini

    private class Digest(val summary: String, val bullets: List<String>, val tags: List<String>, val deepDive: String?)

    private suspend fun requestGemini(batch: List<NewsArticle>): Map<String, Digest> = withContext(Dispatchers.IO) {
        val input = JSONArray().apply {
            batch.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("title", a.title)
                    put("source", a.source)
                    put("text", a.sourceText().take(MAX_AI_INPUT_CHARS))
                })
            }
        }
        val prompt = """
            Ești redactor la o aplicație de știri din România. Pentru fiecare articol din lista JSON de mai jos
            scrie, în limba română, folosind DOAR informațiile din titlu și text (nu inventa fapte, cifre sau nume):
            - "summary": rezumat concis de 2-3 propoziții;
            - "bullets": exact 3 idei cheie scurte (max. 20 de cuvinte fiecare);
            - "tags": exact 3 etichete scurte (1-3 cuvinte, fără #), relevante pentru subiect / loc / persoane;
            - "deep_dive": 2 paragrafe scurte: contextul știrii și de ce contează pentru localnici.
              Dacă textul e prea scurt pentru context, explică doar ce se știe și ce ar urma să se clarifice.
            Răspunde cu un array JSON de obiecte {"id", "summary", "bullets", "tags", "deep_dive"}, câte unul per articol,
            cu același "id" ca în intrare.

            Articole:
            $input
        """.trimIndent()

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
                // Fără „thinking”: răspuns mai rapid și mai puține token-uri consumate din cota gratuită.
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
        }

        val connection = (URL("$GEMINI_ENDPOINT/$GEMINI_MODEL:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                throw IOException("Gemini HTTP $code: $error")
            }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val text = response.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            parseDigests(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDigests(json: String): Map<String, Digest> {
        val array = JSONArray(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val result = mutableMapOf<String, Digest>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val summary = o.optString("summary").trim().takeIf { it.isNotBlank() } ?: continue
            result[id] = Digest(
                summary = summary,
                bullets = o.optJSONArray("bullets").strings().take(3),
                tags = o.optJSONArray("tags").strings().map { it.removePrefix("#") }.take(3),
                deepDive = o.optString("deep_dive").trim().takeIf { it.isNotBlank() }
            )
        }
        return result
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList()
        else (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotBlank) }

    private fun NewsArticle.applyDigest(d: Digest) = copy(
        aiSummary = d.summary,
        aiBullets = d.bullets,
        aiTags = d.tags,
        deepDive = d.deepDive
    )

    // ------------------------------------------------------------------ fallback extractiv

    /**
     * Rezumat fără AI: primele 2-3 propoziții, următoarele 3 ca idei cheie, iar etichetele sunt cele mai
     * frecvente cuvinte semnificative (completate cu orașul și sursa). Nu generează aprofundare.
     */
    private fun NewsArticle.extractiveDigest(city: String): NewsArticle {
        val sentences = splitSentences(sourceText())
        val summarySentences = mutableListOf<String>()
        for (s in sentences) {
            if (summarySentences.size >= 3 || (summarySentences.size >= 2 && summarySentences.sumOf { it.length } > 280)) break
            summarySentences += s
        }
        val summaryText = summarySentences.joinToString(" ").ifBlank { summary.ifBlank { title } }
        val bullets = sentences.drop(summarySentences.size)
            .filter { it.length in 40..240 }
            .take(3)
            .ifEmpty { listOf(title) }
        return copy(
            aiSummary = summaryText,
            aiBullets = bullets,
            aiTags = keywordTags("$title ${sourceText()}", listOf(city, source)),
            deepDive = null
        )
    }

    private fun splitSentences(text: String): List<String> =
        text.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+(?=[\\p{Lu}„\"])"))
            .map { it.trim() }
            .filter { it.length >= 25 }

    private fun keywordTags(text: String, fallbacks: List<String>): List<String> {
        val counts = Regex("[\\p{L}]{5,}").findAll(text)
            .map { it.value }
            .filter { it.lowercase() !in STOPWORDS }
            .groupingBy { it.lowercase() }
            .eachCount()
        val original = Regex("[\\p{L}]{5,}").findAll(text).map { it.value }.associateBy { it.lowercase() }
        val tags = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { entry -> original[entry.key]!!.replaceFirstChar { it.uppercase() } }
            .take(3)
            .toMutableList()
        for (f in fallbacks) {
            if (tags.size >= 3) break
            val tag = f.trim()
            if (tag.isNotEmpty() && tags.none { it.equals(tag, ignoreCase = true) }) tags += tag
        }
        return tags
    }

    private fun readTime(text: String): Int =
        (text.split(Regex("\\s+")).count { it.isNotBlank() } / WORDS_PER_MINUTE.toDouble()).roundToInt().coerceAtLeast(1)

    private companion object {
        const val TAG = "NewsFilter"
        const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val GEMINI_MODEL = "gemini-2.5-flash"
        const val AI_BATCH_SIZE = 5
        const val MAX_AI_INPUT_CHARS = 4_000
        const val EXTRACT_CONCURRENCY = 4
        const val WORDS_PER_MINUTE = 200

        val STOPWORDS = setOf(
            "acest", "aceasta", "această", "acesta", "aceste", "acestea", "acestui", "acestei", "acolo", "astfel",
            "atunci", "avea", "aveau", "avem", "câteva", "către", "despre", "după", "fiind", "foarte", "fost",
            "multe", "mulți", "pentru", "printre", "potrivit", "putea", "poate", "până", "toate", "totuși",
            "trebuie", "unde", "unei", "unui", "vineri", "luni", "marți", "miercuri", "joi", "sâmbătă",
            "duminică", "anului", "spune", "spus", "declarat", "acum", "ceea", "fără", "între", "asupra",
            "cadrul", "urma", "ultimul", "ultima", "lucru", "nostru", "noastră", "dintre", "decât", "având",
            "sunt", "erau", "este", "iată", "vreme", "anul", "zilele", "orele", "minute", "știre", "știri",
            "citește", "articol", "articolul", "google", "news", "publicată", "deschide", "textul", "complet"
        )
    }
}
