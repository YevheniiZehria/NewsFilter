package ro.newsfilter.app.data.local.entity

import ro.newsfilter.app.domain.model.MarketSentiment
import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Funcții de mapare (extensii) între straturile de date.
 *
 * Flux de date: Firestore (DocumentSnapshot) → Domain (NewsArticle) → DB (NewsEntity)
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
    readAt = readAt,
    isBookmarked = isBookmarked,
    userVote = userVote,
    fullContent = fullContent,
    aiTitle = aiTitle,
    aiSummary = aiSummary,
    aiBullets = aiBullets.joinColumn(),
    aiTags = aiTags.joinColumn(),
    deepDive = deepDive,
    affectedCurrency = affectedCurrency,
    marketSentiment = marketSentiment?.name,
    marketImpactNote = marketImpactNote,
    upvotes = upvotes,
    downvotes = downvotes,
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
    readAt = readAt,
    isBookmarked = isBookmarked,
    userVote = userVote,
    fullContent = fullContent,
    aiTitle = aiTitle,
    aiSummary = aiSummary,
    aiBullets = aiBullets.splitColumn(),
    aiTags = aiTags.splitColumn(),
    deepDive = deepDive,
    affectedCurrency = affectedCurrency,
    marketSentiment = MarketSentiment.fromRaw(marketSentiment),
    marketImpactNote = marketImpactNote,
    upvotes = upvotes,
    downvotes = downvotes
)
