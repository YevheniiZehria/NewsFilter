package ro.newsfilter.app.domain.repository

import kotlinx.coroutines.flow.Flow
import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Interfața Repository-ului de știri (strategie **offline-first**).
 *
 * - UI-ul observă cache-ul local ([observeFeed], [getBookmarkedArticles]) și afișează
 *   imediat ce există pe dispozitiv, chiar și offline.
 * - Sincronizarea cu Cloud Firestore ([syncFeed], [syncBookmarks]) doar actualizează cache-ul;
 *   Flow-urile emit automat datele noi.
 * - Metodele `sync*` / `request*` aruncă excepții (rețea / Firestore); apelantul decide cum le afișează
 *   (vezi `Throwable.toUserMessage()`).
 */
interface NewsRepository {

    /** Feed-ul local: articole necitite din [categories], cele mai noi primele. */
    fun observeFeed(categories: List<String>): Flow<List<NewsArticle>>

    /**
     * Descarcă din Firestore știrile noi / actualizate de la ultima sincronizare (incremental)
     * în cache, păstrând starea locală. Filtrarea pe categorii și timp se face local.
     */
    suspend fun syncFeed(categories: List<String>, maxMinutes: Int)

    /** Descarcă favoritele contului (`users/{uid}/bookmarks`) în cache (după autentificare). */
    suspend fun syncBookmarks()

    /**
     * „Aprofundează subiectul”: analiza AI pre-generată de jobul de sincronizare (Gemini),
     * din cache sau din Firestore. Aruncă `UserFacingException` dacă nu este încă disponibilă.
     */
    suspend fun requestDeepDive(articleId: String): String

    /** Salvează votul (like/dislike) în `users/{uid}/votes`. Best effort, nu aruncă. */
    suspend fun sendFeedback(articleId: String, isPositive: Boolean)

    /** Salvează local votul, ca starea Like / Dislike să fie reținută la redeschidere. */
    suspend fun setUserVote(articleId: String, isPositive: Boolean?)

    suspend fun markAsRead(articleId: String)

    /** Resetează istoricul de citire. @return numărul de articole resetate */
    suspend fun resetReadHistory(): Int

    /**
     * Salvează / elimină un articol din favorite: local imediat, apoi în Firestore.
     * @return true dacă și Firestore a confirmat; false dacă a rămas doar local.
     */
    suspend fun setBookmarked(articleId: String, bookmarked: Boolean): Boolean

    fun getBookmarkedArticles(): Flow<List<NewsArticle>>

    /** Un articol individual din cache-ul local. */
    suspend fun getArticleById(id: String): NewsArticle?

    /** Articolul din cache, observat (null = nu există / a fost șters). */
    fun observeArticle(id: String): Flow<NewsArticle?>

    /** Șterge din cache articolele nesincronizate de [daysOld] zile (favoritele rămân). */
    suspend fun clearOldCache(daysOld: Int = 7)
}
