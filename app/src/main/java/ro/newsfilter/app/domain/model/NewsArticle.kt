package ro.newsfilter.app.domain.model

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
 * @property aiSummary Rezumatul Gemini în română; null = AI-ul nu a rulat încă
 * @property aiBullets Smart Digest: 2-3 idei cheie (TL;DR), afișate înaintea articolului
 * @property aiTags Etichete de subiect generate de AI (persoane, locuri, teme)
 * @property deepDive Analiza „Aprofundează subiectul” (generată la cerere în n8n)
 */
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
    val aiSummary: String? = null,
    val aiBullets: List<String> = emptyList(),
    val aiTags: List<String> = emptyList(),
    val deepDive: String? = null
) {
    /** Label-ul categoriei în română, dacă e recunoscută. */
    val categoryLabel: String
        get() = Category.fromSlug(category)?.label ?: category.replaceFirstChar { it.uppercase() }

    /** Emoji-ul categoriei. */
    val categoryEmoji: String
        get() = Category.fromSlug(category)?.emoji ?: "📰"
}
