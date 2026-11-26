package ro.newsfilter.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTO (Data Transfer Object) pentru un articol primit de la API-ul n8n.
 *
 * Decizie tehnică: Moshi cu @JsonClass(generateAdapter = true) și KSP (fără reflecție).
 * Câmpurile sunt intenționat **nullable**: un singur articol incomplet în baza de date
 * (ex. `summary` NULL) făcea ca Moshi să arunce excepție pentru TOATĂ lista, iar feed-ul
 * eșua complet. Acum articolele invalide sunt doar ignorate la mapare (vezi Mappers.kt).
 */
@JsonClass(generateAdapter = true)
data class NewsDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    @Json(name = "summary") val summary: String? = null,
    @Json(name = "article_url") val articleUrl: String? = null,
    @Json(name = "read_time_minutes") val readTimeMinutes: Int? = null,
    @Json(name = "category") val category: String? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    /** Textul integral al articolului, extras prin scraping. */
    @Json(name = "full_content") val fullContent: String? = null,
    /** Rezumatul generat de Gemini AI în română. */
    @Json(name = "ai_summary") val aiSummary: String? = null,
    /** Smart Digest: 2-3 idei cheie (TL;DR) generate în workflow-ul de ingestie. */
    @Json(name = "ai_bullets") val aiBullets: List<String>? = null,
    /** Etichete de subiect (persoane, locuri, teme) generate de AI. */
    @Json(name = "ai_tags") val aiTags: List<String>? = null,
    /** Analiza „Aprofundează subiectul”, dacă a fost deja generată pe server. */
    @Json(name = "deep_dive") val deepDive: String? = null,
    /** Articolul este în favoritele utilizatorului (tabela `bookmarks` din PostgreSQL). */
    @Json(name = "is_bookmarked") val isBookmarked: Boolean? = null
)
