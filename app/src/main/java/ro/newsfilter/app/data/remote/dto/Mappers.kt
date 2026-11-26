package ro.newsfilter.app.data.remote.dto

import ro.newsfilter.app.data.local.entity.NewsEntity
import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Funcții de mapare (extensii) între straturile de date.
 *
 * Flux de date: API (NewsDto) → Domain (NewsArticle) → DB (NewsEntity)
 *               DB (NewsEntity) → Domain (NewsArticle) → UI
 *
 * Listele (idei cheie, etichete) sunt stocate în Room ca text separat prin „\n”,
 * fără TypeConverter.
 */

private const val LIST_SEPARATOR = "\n"

private fun List<String>.joinColumn(): String? =
    map { it.replace(LIST_SEPARATOR, " ").trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(LIST_SEPARATOR)

private fun String?.splitColumn(): List<String> =
    this?.split(LIST_SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/**
 * DTO din API → Model de domeniu. Întoarce null pentru articole incomplete (fără id,
 * titlu sau link), în loc să strice parsarea întregii liste.
 */
fun NewsDto.toDomainOrNull(): NewsArticle? {
    if (id.isNullOrBlank() || title.isNullOrBlank() || articleUrl.isNullOrBlank()) return null
    return NewsArticle(
        id = id,
        title = title,
        source = source.orEmpty(),
        imageUrl = imageUrl,
        summary = summary.orEmpty(),
        articleUrl = articleUrl,
        readTimeMinutes = (readTimeMinutes ?: 1).coerceAtLeast(1),
        category = category.orEmpty().lowercase(),
        publishedAt = publishedAt.orEmpty(),
        isBookmarked = isBookmarked == true,
        fullContent = fullContent,
        aiSummary = aiSummary,
        aiBullets = aiBullets.orEmpty(),
        aiTags = aiTags.orEmpty(),
        deepDive = deepDive
    )
}

/** Model de domeniu → Entity Room (pentru cache) */
fun NewsArticle.toEntity(): NewsEntity = NewsEntity(
    id = id,
    title = title,
    source = source,
    imageUrl = imageUrl,
    summary = summary,
    articleUrl = articleUrl,
    readTimeMinutes = readTimeMinutes,
    category = category,
    publishedAt = publishedAt,
    isRead = isRead,
    isBookmarked = isBookmarked,
    userVote = userVote,
    fullContent = fullContent,
    aiSummary = aiSummary,
    aiBullets = aiBullets.joinColumn(),
    aiTags = aiTags.joinColumn(),
    deepDive = deepDive,
    cachedAt = System.currentTimeMillis()
)

/** Entity Room → Model de domeniu (pentru UI) */
fun NewsEntity.toDomain(): NewsArticle = NewsArticle(
    id = id,
    title = title,
    source = source,
    imageUrl = imageUrl,
    summary = summary,
    articleUrl = articleUrl,
    readTimeMinutes = readTimeMinutes,
    category = category,
    publishedAt = publishedAt,
    isRead = isRead,
    isBookmarked = isBookmarked,
    userVote = userVote,
    fullContent = fullContent,
    aiSummary = aiSummary,
    aiBullets = aiBullets.splitColumn(),
    aiTags = aiTags.splitColumn(),
    deepDive = deepDive
)
