package ro.newsfilter.app.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.android.gms.tasks.Task
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ro.newsfilter.app.data.local.AppDatabase
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
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.NewsRepository
import java.io.IOException
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
    private val database: AppDatabase,
    private val networkMonitor: NetworkMonitor
) : NewsRepository {

    override fun observeFeed(categories: List<String>): Flow<List<NewsArticle>> =
        newsDao.observeFeed(categories, FEED_LIMIT).map { list -> list.map { it.toDomain() } }

    /**
     * Sincronizează toate categoriile (Room filtrează după [categories] și timpul de lectură),
     * deci schimbarea categoriilor nu cere citiri noi. [maxMinutes] nu influențează cererea.
     */
    override suspend fun syncFeed(categories: List<String>, maxMinutes: Int) {
        ensureOnline()
        val uid = requireUid()
        val articles = firestore.collection(FirestorePaths.ARTICLES)
        val lastSync = preferences.lastFeedSync()
        val docs = mutableListOf<DocumentSnapshot>()

        // Source.SERVER: altfel, pe o rețea proastă, Firestore întoarce în tăcere cache-ul din memorie (gol).
        if (lastSync == 0L) {
            // Prima sincronizare (cont nou / după deconectare): doar cele mai recente știri.
            docs += articles.orderBy(UPDATED_AT, Query.Direction.DESCENDING)
                .limit(INITIAL_LIMIT).get(Source.SERVER).await().documents
        } else {
            // Toate articolele unei rulări a jobului au același `updatedAt`, deci paginile următoare
            // continuă de la ultimul document (startAfter departajează după id), nu de la timestamp.
            val newer = articles.whereGreaterThan(UPDATED_AT, lastSync.toTimestamp())
                .orderBy(UPDATED_AT).limit(PAGE_SIZE)
            var last: DocumentSnapshot? = null
            for (page in 0 until MAX_PAGES) {
                val query = last?.let { newer.startAfter(it) } ?: newer
                val batch = query.get(Source.SERVER).await().documents
                docs += batch
                if (batch.size < PAGE_SIZE) break
                last = batch.last()
            }
        }

        // Deconectare în timpul citirii: datele nu mai aparțin sesiunii curente (cache-ul a fost golit).
        if (sessionManager.currentUid != uid) return
        val saved = upsertKeepingLocalState(docs.mapNotNull { it.toNewsArticleOrNull() }, serverBookmarksWin = false)
        // Cursorul avansează doar până la ultima rulare citită complet: dacă ne-am oprit la MAX_PAGES
        // în mijlocul unei rulări, documentele ei rămase vin la sincronizarea următoare (cu „>=” ar fi
        // re-citite toate, cu „>” s-ar pierde), așa că le re-citim prin cursorul rulării precedente.
        val stamps = docs.mapNotNull { it.getTimestamp(UPDATED_AT) }
        val complete = docs.size < MAX_PAGES * PAGE_SIZE
        val newCursor = if (complete) stamps.maxOrNull() else stamps.maxOrNull()?.let { top -> stamps.filter { it < top }.maxOrNull() }
        newCursor?.let { preferences.setLastFeedSync(it.toMicros()) }
        Log.d(TAG, "Sincronizare feed: ${docs.size} documente citite, $saved salvate în cache")
    }

    override suspend fun syncBookmarks() {
        ensureOnline()
        val docs = userCollection(FirestorePaths.BOOKMARKS).get(Source.SERVER).await().documents
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

    override suspend fun sendFeedback(articleId: String, isPositive: Boolean) {
        try {
            val ref = userCollection(FirestorePaths.VOTES).document(articleId)
            awaitWrite { ref.set(mapOf("positive" to isPositive, "votedAt" to FieldValue.serverTimestamp())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Votul nu a putut fi salvat în Firestore: ${e.message}")
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
            val ref = userCollection(FirestorePaths.BOOKMARKS).document(articleId)
            if (bookmarked) {
                val article = newsDao.getArticleById(articleId)?.toDomain() ?: return false
                awaitWrite { ref.set(article.toBookmarkData()) }
            } else {
                awaitWrite { ref.delete() }
            }
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

    override fun observeArticle(id: String): Flow<NewsArticle?> =
        newsDao.observeArticleById(id).map { it?.toDomain() }.distinctUntilChanged()

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
        // Citire + scriere în aceeași tranzacție: un „citit” / „salvat” apăsat în timpul
        // sincronizării nu mai poate fi suprascris cu starea veche.
        return database.withTransaction {
            val existing = newsDao.getArticlesByIds(articles.map { it.id }).associateBy { it.id }
            val entities = articles.map { article ->
                val local = existing[article.id]
                // Copia din favorite e „înghețată” la momentul salvării: dacă local avem deja
                // analiza AI (venită ulterior prin feed), o păstrăm pe cea locală.
                val base = if (serverBookmarksWin && local?.aiSummary != null && article.aiSummary == null) {
                    local.toDomain()
                } else {
                    article
                }
                base.copy(
                    isRead = local?.isRead ?: false,
                    isBookmarked = if (serverBookmarksWin || local == null) article.isBookmarked else local.isBookmarked,
                    userVote = local?.userVote,
                    deepDive = base.deepDive ?: local?.deepDive
                ).toEntity()
            }
            newsDao.insertAll(entities)
            entities.size
        }
    }

    /**
     * Așteaptă confirmarea unei scrieri Firestore, cu limită de timp: offline, `await()` nu se
     * termină niciodată. @return false dacă dispozitivul e offline sau serverul nu a confirmat
     * (scrierea rămâne în coada Firestore și pleacă la revenirea rețelei, cât timp aplicația rulează).
     */
    private suspend fun awaitWrite(write: () -> Task<Void>): Boolean {
        if (!networkMonitor.isCurrentlyOnline()) {
            write()
            return false
        }
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) { write().await(); true } ?: false
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
        const val INITIAL_LIMIT = 200L
        const val PAGE_SIZE = 200L
        const val MAX_PAGES = 3
        const val WRITE_TIMEOUT_MS = 10_000L

        /** Timestamp-urile Firestore au precizie de microsecunde: le păstrăm exact (fără re-citiri). */
        fun Timestamp.toMicros(): Long = seconds * 1_000_000 + nanoseconds / 1_000
        fun Long.toTimestamp(): Timestamp = Timestamp(this / 1_000_000, ((this % 1_000_000) * 1_000).toInt())
    }
}
