package ro.newsfilter.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Model de domeniu pur pentru un articol de știri.
 * Este independent de sursa de date (rețea sau bază de date locală).
 *
 * @property id Identificator unic al articolului (UUID din PostgreSQL)
 * @property title Titlul articolului
 * @property source Sursa originală (ex: "Digi24", "HotNews")
 * @property imageUrl URL-ul imaginii asociate articolului
 * @property summary Descrierea din RSS
 * @property articleUrl Link complet către articolul original
 * @property readTimeMinutes Timp estimat de citire în minute (cuvinte / 200)
 * @property category Categoria articolului (slug: tech, sport etc.)
 * @property publishedAt Data publicării (ISO 8601 string)
 * @property isRead Dacă utilizatorul a citit deja articolul
 * @property isBookmarked Dacă articolul este salvat la favorite
 * @property userVote Votul utilizatorului (true = îmi place, false = nu-mi place, null = nevotat)
 * @property fullContent Textul integral (scraping); null = indisponibil
 * @property aiTitle Titlul tradus în română de Gemini; null = articol încă neprocesat (se afișează originalul)
 * @property aiSummary Rezumatul Gemini în română; null = AI-ul nu a rulat încă
 * @property aiBullets Smart Digest: 2-3 idei cheie (TL;DR), afișate înaintea articolului
 * @property aiTags Etichete de subiect generate de AI (persoane, locuri, teme)
 * @property deepDive Analiza „Aprofundează subiectul” (pre-generată de Gemini în jobul de sincronizare)
 * @property affectedCurrency Perechea valutară influențată (ex. „EUR/RON”), după AI; null = fără impact
 * @property marketSentiment Direcția estimată a perechii
 * @property marketImpactNote Explicația AI într-o propoziție
 * @property upvotes / [downvotes] Voturile comunității (contoare din `articles/{id}`)
 */
@Immutable // listele nu se modifică după creare → Compose poate sări recompozițiile cardurilor
data class NewsArticle(
    val id: String,
    val title: String,
    val source: String,
    val imageUrl: String?,
    val summary: String,
    val articleUrl: String,
    val readTimeMinutes: Int,
    val category: String,
    val publishedAt: String,
    val isRead: Boolean = false,
    val isBookmarked: Boolean = false,
    val userVote: Boolean? = null,
    val fullContent: String? = null,
    val aiTitle: String? = null,
    val aiSummary: String? = null,
    val aiBullets: List<String> = emptyList(),
    val aiTags: List<String> = emptyList(),
    val deepDive: String? = null,
    val affectedCurrency: String? = null,
    val marketSentiment: MarketSentiment? = null,
    val marketImpactNote: String? = null,
    val upvotes: Int = 0,
    val downvotes: Int = 0
) {
    /** Titlul afișat: traducerea în română, cu fallback la titlul original (engleză). */
    val displayTitle: String
        get() = aiTitle?.takeIf { it.isNotBlank() } ?: title

    /** Rezumatul afișat: rezumatul AI în română, cu fallback la descrierea RSS (engleză). */
    val displaySummary: String
        get() = aiSummary?.takeIf { it.isNotBlank() } ?: summary

    /** True după ce jobul de sincronizare a generat conținutul în română (Gemini). */
    val isAiProcessed: Boolean
        get() = !aiSummary.isNullOrBlank()

    /**
     * Articol în engleză care așteaptă traducerea AI (indicatorul „EN · traducere în curs”).
     * Știrile locale (Google News, deja în română, fără Gemini) nu sunt traduse niciodată.
     */
    val needsTranslation: Boolean
        get() = !isAiProcessed && category != Category.LOCAL.slug

    /** Procentul cititorilor care au găsit știrea utilă; null sub [MIN_VOTES_FOR_BADGE] voturi. */
    val usefulPercent: Int?
        get() {
            val total = upvotes + downvotes
            return if (total < MIN_VOTES_FOR_BADGE) null else Math.round(upvotes * 100f / total)
        }

    /** Label-ul categoriei în română, dacă e recunoscută. */
    val categoryLabel: String
        get() = Category.fromSlug(category)?.label ?: category.replaceFirstChar { it.uppercase() }

    /** Emoji-ul categoriei. */
    val categoryEmoji: String
        get() = Category.fromSlug(category)?.emoji ?: "📰"

    companion object {
        const val MIN_VOTES_FOR_BADGE = 3
    }
}
