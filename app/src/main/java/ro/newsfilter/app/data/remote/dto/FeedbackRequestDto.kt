package ro.newsfilter.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTO pentru trimiterea feedback-ului pe un articol (like/dislike).
 * Trimis ca body JSON la POST /feedback; utilizatorul este dedus din token-ul de sesiune.
 */
@JsonClass(generateAdapter = true)
data class FeedbackRequestDto(
    @Json(name = "article_id") val articleId: String,
    @Json(name = "is_positive") val isPositive: Boolean
)

/** POST /bookmarks: salvează (`bookmarked = true`) sau elimină un articol din favoritele contului. */
@JsonClass(generateAdapter = true)
data class BookmarkRequestDto(
    @Json(name = "article_id") val articleId: String,
    @Json(name = "bookmarked") val bookmarked: Boolean
)

/** POST /deep-dive: cere analiza AI „Aprofundează subiectul” pentru un articol. */
@JsonClass(generateAdapter = true)
data class DeepDiveRequestDto(
    @Json(name = "article_id") val articleId: String
)

@JsonClass(generateAdapter = true)
data class DeepDiveResponseDto(
    @Json(name = "article_id") val articleId: String,
    @Json(name = "deep_dive") val deepDive: String
)
