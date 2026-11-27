package ro.newsfilter.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity Room care stochează articolele de știri local (cache offline + favorite + citite).
 *
 * Decizie tehnică: O singură tabelă "news" servește atât pentru cache,
 * cât și pentru favorite și articole citite. Câmpurile isRead, isBookmarked și userVote
 * sunt flag-uri locale care nu se trimit la API. Câmpul cachedAt permite
 * ștergerea automată a articolelor vechi din cache.
 */
@Entity(tableName = "news")
data class NewsEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "image_url")
    val imageUrl: String?,

    @ColumnInfo(name = "summary")
    val summary: String,

    @ColumnInfo(name = "article_url")
    val articleUrl: String,

    @ColumnInfo(name = "read_time_minutes")
    val readTimeMinutes: Int,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "published_at")
    val publishedAt: String,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean = false,

    /** Votul utilizatorului: true = „Îmi place”, false = „Nu-mi place”, null = nevotat. */
    @ColumnInfo(name = "user_vote")
    val userVote: Boolean? = null,

    /**
     * Textul integral al articolului (extras prin scraping + salvat în PostgreSQL).
     * Null dacă articolul nu a fost încă îmbogățit sau dacă scraping-ul a eșuat.
     */
    @ColumnInfo(name = "full_content")
    val fullContent: String? = null,

    /** Titlul tradus în română de AI (Gemini); null = se afișează titlul original. */
    @ColumnInfo(name = "ai_title")
    val aiTitle: String? = null,

    /**
     * Rezumatul generat de AI (Gemini) în română.
     * Null dacă generarea AI nu a rulat încă pentru acest articol.
     */
    @ColumnInfo(name = "ai_summary")
    val aiSummary: String? = null,

    /** Smart Digest: ideile cheie (TL;DR), separate prin „
” (fără TypeConverter). */
    @ColumnInfo(name = "ai_bullets")
    val aiBullets: String? = null,

    /** Etichetele de subiect generate de AI, separate prin „
”. */
    @ColumnInfo(name = "ai_tags")
    val aiTags: String? = null,

    /** Analiza „Aprofundează subiectul” (cache local; disponibilă și offline după prima cerere). */
    @ColumnInfo(name = "deep_dive")
    val deepDive: String? = null,

    /** Market Pulse: perechea valutară influențată, direcția (UP/DOWN/NEUTRAL) și nota AI. */
    @ColumnInfo(name = "affected_currency")
    val affectedCurrency: String? = null,

    @ColumnInfo(name = "market_sentiment")
    val marketSentiment: String? = null,

    @ColumnInfo(name = "market_impact_note")
    val marketImpactNote: String? = null,

    /** Voturile comunității (contoarele din Firestore; actualizate optimist la votul propriu). */
    @ColumnInfo(name = "upvotes", defaultValue = "0")
    val upvotes: Int = 0,

    @ColumnInfo(name = "downvotes", defaultValue = "0")
    val downvotes: Int = 0,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis()
)

