package ro.newsfilter.app.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import ro.newsfilter.app.core.util.NetworkMonitor
import ro.newsfilter.app.data.local.dao.NewsDao
import ro.newsfilter.app.data.remote.NewsApiService
import ro.newsfilter.app.data.remote.dto.BookmarkRequestDto
import ro.newsfilter.app.data.remote.dto.DeepDiveRequestDto
import ro.newsfilter.app.data.remote.dto.FeedbackRequestDto
import ro.newsfilter.app.data.remote.dto.NewsDto
import ro.newsfilter.app.data.remote.dto.toDomain
import ro.newsfilter.app.data.remote.dto.toDomainOrNull
import ro.newsfilter.app.data.remote.dto.toEntity
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.NewsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementarea [NewsRepository] — punctul în care se întâlnesc stocarea **cloud**
 * (PostgreSQL prin API-ul n8n) și stocarea **locală** (Room).
 *
 * Flux de date (offline-first):
 * ```
 * UI ◄── Flow ── Room (sursa unică de adevăr) ◄── syncFeed() ◄── GET /news (n8n)
 *  │                                                   ▲
 *  └── acțiuni (citit / salvat / vot) ──► Room ──► POST /bookmarks, /feedback
 * ```
 * - Aplicația pornește instantaneu din cache; sincronizarea rulează în fundal
 *   (la deschidere, pull-to-refresh, periodic prin `NewsSyncWorker`).
 * - Starea personală (citit, vot) trăiește în Room; favoritele sunt și pe server,
 *   ca să revină după reautentificare / pe alt dispozitiv.
 */
@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val apiService: NewsApiService,
    private val newsDao: NewsDao,
    private val networkMonitor: NetworkMonitor
) : NewsRepository {

    override fun observeFeed(categories: List<String>): Flow<List<NewsArticle>> =
        newsDao.observeFeed(categories, FEED_LIMIT).map { list -> list.map { it.toDomain() } }

    override suspend fun syncFeed(categories: List<String>, maxMinutes: Int) {
        ensureOnline()
        val exclude = newsDao.getRecentReadIds(MAX_EXCLUDE_IDS)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

        val remote = apiService.getNews(
            categories = categories.joinToString(","),
            maxMinutes = maxMinutes,
            exclude = exclude
        )
        val saved = upsertKeepingLocalState(remote, serverBookmarksWin = false)
        Log.d(TAG, "Sincronizare feed: ${remote.size} primite, $saved salvate în cache")
    }

    override suspend fun syncBookmarks() {
        ensureOnline()
        val saved = upsertKeepingLocalState(apiService.getBookmarks(), serverBookmarksWin = true)
        Log.d(TAG, "Sincronizare favorite: $saved articole")
    }

    override suspend fun requestServerRefresh(): Boolean {
        ensureOnline()
        val response = apiService.forceRefresh()
        return when {
            response.isSuccessful -> true
            response.code() == 429 -> false
            else -> throw HttpException(response)
        }
    }

    override suspend fun requestDeepDive(articleId: String): String {
        newsDao.getArticleById(articleId)?.deepDive?.takeIf { it.isNotBlank() }?.let { return it }
        ensureOnline()
        val text = apiService.deepDive(DeepDiveRequestDto(articleId)).deepDive.trim()
        if (text.isEmpty()) throw IOException("Analiza AI este goală")
        newsDao.setDeepDive(articleId, text)
        return text
    }

    override suspend fun sendFeedback(articleId: String, isPositive: Boolean) {
        try {
            apiService.sendFeedback(FeedbackRequestDto(articleId = articleId, isPositive = isPositive))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Feedback-ul nu a putut fi trimis: ${e.message}")
        }
    }

    override suspend fun setUserVote(articleId: String, isPositive: Boolean?) {
        newsDao.setUserVote(articleId, isPositive)
    }

    override suspend fun markAsRead(articleId: String) {
        newsDao.markAsRead(articleId)
    }

    override suspend fun resetReadHistory(): Int = newsDao.resetReadHistory()

    override suspend fun setBookmarked(articleId: String, bookmarked: Boolean): Boolean {
        newsDao.setBookmarked(articleId, bookmarked)
        return try {
            apiService.setBookmark(BookmarkRequestDto(articleId, bookmarked)).isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Favoritul nu a putut fi sincronizat cu serverul: ${e.message}")
            false
        }
    }

    override fun getBookmarkedArticles(): Flow<List<NewsArticle>> =
        newsDao.getBookmarkedArticles().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getArticleById(id: String): NewsArticle? =
        newsDao.getArticleById(id)?.toDomain()

    override suspend fun clearOldCache(daysOld: Int) {
        val cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
        newsDao.clearOldCache(cutoffTime)
    }

    // ------------------------------------------------------------------

    /**
     * Salvează articolele primite păstrând starea locală (citit, vot, analiza descărcată).
     * Favoritele: la /news decide starea locală (acțiunea utilizatorului e mai nouă);
     * la /bookmarks decide serverul ([serverBookmarksWin]).
     * @return numărul de articole valide salvate (cele incomplete sunt ignorate)
     */
    private suspend fun upsertKeepingLocalState(remote: List<NewsDto>, serverBookmarksWin: Boolean): Int {
        val articles = remote.mapNotNull { it.toDomainOrNull() }
        if (articles.isEmpty()) return 0

        val existing = newsDao.getArticlesByIds(articles.map { it.id }).associateBy { it.id }
        val entities = articles.map { article ->
            val local = existing[article.id]
            article.copy(
                isRead = local?.isRead ?: false,
                isBookmarked = if (serverBookmarksWin || local == null) article.isBookmarked else local.isBookmarked,
                userVote = local?.userVote,
                deepDive = article.deepDive ?: local?.deepDive
            ).toEntity()
        }
        newsDao.insertAll(entities)
        return entities.size
    }

    /** Evită așteptarea timeout-ului de conectare când dispozitivul e clar offline. */
    private fun ensureOnline() {
        if (!networkMonitor.isCurrentlyOnline()) throw IOException("Dispozitivul este offline")
    }

    private companion object {
        const val TAG = "NewsFilter"
        const val FEED_LIMIT = 50

        /** ~150 × 37 caractere ≈ 5,5 KB: încape sigur în URL-ul cererii GET /news. */
        const val MAX_EXCLUDE_IDS = 150
    }
}
