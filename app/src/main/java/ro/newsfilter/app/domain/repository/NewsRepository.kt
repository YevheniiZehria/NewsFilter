package ro.newsfilter.app.domain.repository

import kotlinx.coroutines.flow.Flow
import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Interfața Repository-ului de știri (strategie **offline-first**).
 *
 * - UI-ul observă cache-ul local ([observeFeed], [getBookmarkedArticles]) și afișează
 *   imediat ce există pe dispozitiv, chiar și offline.
 * - Sincronizarea cu n8n ([syncFeed], [syncBookmarks]) doar actualizează cache-ul;
 *   Flow-urile emit automat datele noi.
 * - Metodele `sync*` / `request*` aruncă excepții de rețea; apelantul decide cum le afișează
 *   (vezi `Throwable.toUserMessage()`).
 */
interface NewsRepository {

    /** Feed-ul local: articole necitite din [categories], cele mai noi primele. */
    fun observeFeed(categories: List<String>): Flow<List<NewsArticle>>

    /** Descarcă cele mai recente știri de la n8n în cache (păstrând starea locală). */
    suspend fun syncFeed(categories: List<String>, maxMinutes: Int)

    /** Descarcă favoritele contului de pe server în cache (după autentificare). */
    suspend fun syncBookmarks()

    /**
     * „Forțează actualizarea”: cere n8n să ruleze acum workflow-ul de ingestie.
     * @return true = pornit; false = o actualizare a fost pornită recent (limitare server).
     */
    suspend fun requestServerRefresh(): Boolean

    /** „Aprofundează subiectul”: analiza AI din cache sau generată de n8n (Gemini). */
    suspend fun requestDeepDive(articleId: String): String

    /** Trimite feedback (like/dislike) pentru un articol. Best effort, nu aruncă. */
    suspend fun sendFeedback(articleId: String, isPositive: Boolean)

    /** Salvează local votul, ca starea Like / Dislike să fie reținută la redeschidere. */
    suspend fun setUserVote(articleId: String, isPositive: Boolean?)

    suspend fun markAsRead(articleId: String)

    /** Resetează istoricul de citire. @return numărul de articole resetate */
    suspend fun resetReadHistory(): Int

    /**
     * Salvează / elimină un articol din favorite: local imediat, apoi pe server.
     * @return true dacă și serverul a confirmat; false dacă a rămas doar local.
     */
    suspend fun setBookmarked(articleId: String, bookmarked: Boolean): Boolean

    fun getBookmarkedArticles(): Flow<List<NewsArticle>>

    /** Un articol individual din cache-ul local. */
    suspend fun getArticleById(id: String): NewsArticle?

    /** Șterge din cache articolele nesincronizate de [daysOld] zile (favoritele rămân). */
    suspend fun clearOldCache(daysOld: Int = 7)
}
