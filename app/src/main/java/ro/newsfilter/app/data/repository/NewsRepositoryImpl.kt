package ro.newsfilter.app.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import ro.newsfilter.app.core.auth.SessionManager
import ro.newsfilter.app.core.network.UserFacingException
import ro.newsfilter.app.core.util.NetworkMonitor
import ro.newsfilter.app.data.local.dao.NewsDao
import ro.newsfilter.app.data.local.entity.toDomain
import ro.newsfilter.app.data.local.entity.toEntity
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.data.remote.firestore.FirestorePaths
import ro.newsfilter.app.data.remote.firestore.toBookmarkData
import ro.newsfilter.app.data.remote.firestore.toNewsArticleOrNull
import ro.newsfilter.app.data.remote.localnews.GoogleNewsRssSource
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.NewsRepository
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementarea [NewsRepository] — punctul în care se întâlnesc stocarea **cloud**
 * (Cloud Firestore) și stocarea **locală** (Room).
 *
 * Flux de date (offline-first):
 * ```
 * UI ◄── Flow ── Room (sursa unică de adevăr) ◄── syncFeed() ◄── Firestore `articles`
 *  │                                                   ▲             (scrise de jobul sync/)
 *  └── acțiuni (citit / salvat / vot) ──► Room ──► users/{uid}/bookmarks, users/{uid}/votes
 *                                                   (+ contoarele upvotes / downvotes din articles/{id})
 * ```
 * - Aplicația pornește instantaneu din cache; sincronizarea rulează în fundal
 *   (la deschidere, pull-to-refresh, periodic prin `NewsSyncWorker`).
 * - Sincronizarea este **incrementală** (doar documentele cu `updatedAt` mai nou decât ultima
 *   sincronizare), ca numărul de citiri să rămână mic (planul gratuit: 50.000 / zi).
 * - Starea personală (citit, vot) trăiește în Room; favoritele și voturile sunt și în Firestore,
 *   ca să revină după reautentificare / pe alt dispozitiv.
 */
@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val sessionManager: SessionManager,
    private val preferences: UserPreferencesRepository,
    private val newsDao: NewsDao,
    private val networkMonitor: NetworkMonitor,
    private val localNewsSource: GoogleNewsRssSource
) : NewsRepository {

    /** Voturile rapide (ex. 👍 apoi 👎) se trimit pe rând: fiecare tranziție pornește de la cea anterioară. */
    private val voteMutex = Mutex()

    override fun observeFeed(categories: List<String>, query: String): Flow<List<NewsArticle>> =
        newsDao.observeFeed(categories, query, FEED_LIMIT).map { list -> list.map { it.toDomain() } }

    /**
     * Știrile locale vin direct de pe dispozitiv (Google News RSS), nu din Firestore.
     * Știrile locale care nu mai apar în rezultat (alt oraș / mai vechi) și nu sunt salvate se
     * șterg, ca feed-ul „📍” să arate doar locația curentă; starea locală (citit, salvat) a
     * celorlalte se păstrează. O eroare de rețea nu șterge nimic (cache-ul rămâne pentru offline).
     */
    override suspend fun syncLocalNews(city: String, country: String) {
        if (city.isBlank() && country.isBlank()) return
        ensureOnline()
        val articles = localNewsSource.fetch(city.trim(), country.trim())
        newsDao.deleteStaleInCategory(Category.LOCAL.slug, articles.map { it.id })
        val saved = upsertKeepingLocalState(articles, serverBookmarksWin = false)
        Log.d(TAG, "Știri locale ($city, $country): $saved salvate în cache")
    }

    /**
     * Sincronizează toate categoriile (Room filtrează după [categories] și timpul de lectură),
     * deci schimbarea categoriilor nu cere citiri noi. [maxMinutes] nu influențează cererea.
     */
    override suspend fun syncFeed(categories: List<String>, maxMinutes: Int) {
        ensureOnline()
        requireUid()
        val articles = firestore.collection(FirestorePaths.ARTICLES)
        val lastSync = preferences.lastFeedSync()
        val docs = mutableListOf<DocumentSnapshot>()

        if (lastSync == 0L) {
            // Prima sincronizare (cont nou / după deconectare): doar cele mai recente știri.
            docs += articles.orderBy(UPDATED_AT, Query.Direction.DESCENDING)
                .limit(INITIAL_LIMIT).get().await().documents
        } else {
            var cursor = lastSync.toTimestamp()
            for (page in 0 until MAX_PAGES) {
                val batch = articles.whereGreaterThan(UPDATED_AT, cursor)
                    .orderBy(UPDATED_AT).limit(PAGE_SIZE).get().await().documents
                docs += batch
                if (batch.size < PAGE_SIZE) break
                cursor = batch.last().getTimestamp(UPDATED_AT) ?: break
            }
        }

        val saved = upsertKeepingLocalState(docs.mapNotNull { it.toNewsArticleOrNull() }, serverBookmarksWin = false)
        docs.mapNotNull { it.getTimestamp(UPDATED_AT) }.maxOrNull()?.let { preferences.setLastFeedSync(it.toMicros()) }
        Log.d(TAG, "Sincronizare feed: ${docs.size} documente citite, $saved salvate în cache")
    }

    override suspend fun syncBookmarks() {
        ensureOnline()
        val docs = userCollection(FirestorePaths.BOOKMARKS).get().await().documents
        val saved = upsertKeepingLocalState(docs.mapNotNull { it.toNewsArticleOrNull(isBookmarked = true) }, serverBookmarksWin = true)
        Log.d(TAG, "Sincronizare favorite: $saved articole")
    }

    override suspend fun requestDeepDive(articleId: String): String {
        newsDao.getArticleById(articleId)?.deepDive?.takeIf { it.isNotBlank() }?.let { return it }
        ensureOnline()
        val doc = firestore.collection(FirestorePaths.ARTICLES).document(articleId).get().await()
        val text = doc.getString("deepDive")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw UserFacingException(
                "Aprofundarea pentru acest articol nu este încă gata. Analiza AI se generează automat — revino în câteva minute."
            )
        newsDao.setDeepDive(articleId, text)
        return text
    }

    /**
     * Votul comunității. Room se actualizează optimist (votul + contoarele); în Firestore votul
     * (`users/{uid}/votes/{id}`) și `FieldValue.increment(±1)` pe `articles/{id}` pleacă într-un
     * singur [com.google.firebase.firestore.WriteBatch], iar regulile verifică tranziția
     * (vot nou / schimbat / retras) față de votul anterior de pe server.
     */
    override suspend fun setVote(articleId: String, isPositive: Boolean?): Boolean = voteMutex.withLock {
        val localBefore = newsDao.getArticleById(articleId)?.userVote
        if (localBefore == isPositive) return@withLock true
        newsDao.applyVote(
            articleId, isPositive,
            upDelta = voteDelta(localBefore, isPositive, positive = true),
            downDelta = voteDelta(localBefore, isPositive, positive = false)
        )
        try {
            ensureOnline()
            val voteRef = userCollection(FirestorePaths.VOTES).document(articleId)
            // Votul anterior de pe server (poate diferi de cel local, ex. după reautentificare):
            // contoarele se corectează față de el, altfel regulile resping batch-ul.
            val serverBefore = voteRef.get(Source.SERVER).await().takeIf { it.exists() }?.getBoolean("positive")
            if (serverBefore == isPositive) return@withLock true

            val counters = mutableMapOf<String, Any>(UPDATED_AT to FieldValue.serverTimestamp())
            voteDelta(serverBefore, isPositive, positive = true).takeIf { it != 0 }
                ?.let { counters[UPVOTES] = FieldValue.increment(it.toLong()) }
            voteDelta(serverBefore, isPositive, positive = false).takeIf { it != 0 }
                ?.let { counters[DOWNVOTES] = FieldValue.increment(it.toLong()) }

            val batch = firestore.batch()
            if (isPositive == null) {
                batch.delete(voteRef)
            } else {
                batch.set(voteRef, mapOf("positive" to isPositive, "votedAt" to FieldValue.serverTimestamp()))
            }
            batch.update(firestore.collection(FirestorePaths.ARTICLES).document(articleId), counters)
            batch.commit().await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Votul nu a putut fi salvat în Firestore: ${e.message}")
            // Revenim la starea anterioară: contoarele locale nu trebuie să conțină voturi netrimise.
            newsDao.applyVote(
                articleId, localBefore,
                upDelta = voteDelta(isPositive, localBefore, positive = true),
                downDelta = voteDelta(isPositive, localBefore, positive = false)
            )
            false
        }
    }

    override fun observeTrending(query: String): Flow<List<NewsArticle>> {
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        return newsDao.observeTrending(startOfToday, query, FEED_LIMIT).map { list -> list.map { it.toDomain() } }
    }

    override suspend fun markAsRead(articleId: String) {
        newsDao.markAsRead(articleId)
    }

    override suspend fun resetReadHistory(): Int = newsDao.resetReadHistory()

    override suspend fun setBookmarked(articleId: String, bookmarked: Boolean): Boolean {
        newsDao.setBookmarked(articleId, bookmarked)
        return try {
            val ref = userCollection(FirestorePaths.BOOKMARKS).document(articleId)
            if (bookmarked) {
                val article = newsDao.getArticleById(articleId)?.toDomain() ?: return false
                ref.set(article.toBookmarkData()).await()
            } else {
                ref.delete().await()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Favoritul nu a putut fi sincronizat cu Firestore: ${e.message}")
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
     * Favoritele: la feed decide starea locală (acțiunea utilizatorului e mai nouă);
     * la favoritele contului decide Firestore ([serverBookmarksWin]).
     * @return numărul de articole salvate
     */
    private suspend fun upsertKeepingLocalState(articles: List<NewsArticle>, serverBookmarksWin: Boolean): Int {
        if (articles.isEmpty()) return 0
        val existing = newsDao.getArticlesByIds(articles.map { it.id }).associateBy { it.id }
        val entities = articles.map { article ->
            val local = existing[article.id]
            article.copy(
                isRead = local?.isRead ?: false,
                isBookmarked = if (serverBookmarksWin || local == null) article.isBookmarked else local.isBookmarked,
                userVote = local?.userVote,
                deepDive = article.deepDive ?: local?.deepDive,
                // Copiile din favorite nu au contoare: păstrăm valorile venite din `articles`.
                upvotes = if (serverBookmarksWin) local?.upvotes ?: 0 else article.upvotes,
                downvotes = if (serverBookmarksWin) local?.downvotes ?: 0 else article.downvotes
            ).toEntity()
        }
        newsDao.insertAll(entities)
        return entities.size
    }

    private fun requireUid(): String =
        sessionManager.currentUid ?: throw UserFacingException("Sesiunea a expirat. Autentifică-te din nou.")

    private fun userCollection(name: String) =
        firestore.collection(FirestorePaths.USERS).document(requireUid()).collection(name)

    /** Evită așteptarea timeout-ului când dispozitivul e clar offline. */
    private fun ensureOnline() {
        if (!networkMonitor.isCurrentlyOnline()) throw IOException("Dispozitivul este offline")
    }

    private companion object {
        const val TAG = "NewsFilter"
        const val FEED_LIMIT = 50
        const val UPDATED_AT = "updatedAt"
        const val UPVOTES = "upvotes"
        const val DOWNVOTES = "downvotes"
        const val INITIAL_LIMIT = 200L
        const val PAGE_SIZE = 200L
        const val MAX_PAGES = 3

        /** Variația contorului (upvotes dacă [positive], altfel downvotes) la trecerea [before] → [after]. */
        fun voteDelta(before: Boolean?, after: Boolean?, positive: Boolean): Int =
            (if (after == positive) 1 else 0) - (if (before == positive) 1 else 0)

        /** Timestamp-urile Firestore au precizie de microsecunde: le păstrăm exact (fără re-citiri). */
        fun Timestamp.toMicros(): Long = seconds * 1_000_000 + nanoseconds / 1_000
        fun Long.toTimestamp(): Timestamp = Timestamp(this / 1_000_000, ((this % 1_000_000) * 1_000).toInt())
    }
}
