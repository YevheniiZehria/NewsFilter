package ro.newsfilter.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ro.newsfilter.app.data.local.entity.NewsEntity

/**
 * DAO (Data Access Object) pentru operații pe tabela "news".
 *
 * Decizie tehnică (offline-first): UI-ul observă tabela prin Flow-uri reactive, iar
 * sincronizarea cu Firestore doar scrie în tabelă. Orice modificare (articole noi, citit,
 * salvat) apare automat pe ecran, fără reîncărcări manuale.
 */
@Dao
interface NewsDao {

    /** Inserează sau actualizează articole. REPLACE: aceeași cheie (id) → rând nou. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<NewsEntity>)

    /**
     * Feed-ul „Pentru tine”: articolele necitite din categoriile alese, cele mai noi primele.
     * Flow reactiv — sursa unică de adevăr pentru ecranul principal.
     *
     * Căutarea [query] (gol = fără filtru) caută în titlu (original și tradus de AI), descriere,
     * rezumatul AI și etichete. Caracterele speciale LIKE (`%`, `_`, `\`) trebuie escapate de
     * apelant cu `\`. Notă: LIKE din SQLite ignoră majusculele doar pentru literele ASCII (ex. „ș” ≠ „Ș”).
     */
    @Query(
        """
        SELECT * FROM news
        WHERE category IN (:categories) AND is_read = 0
          AND (:query = ''
               OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR ai_title LIKE '%' || :query || '%' ESCAPE '\'
               OR summary LIKE '%' || :query || '%' ESCAPE '\'
               OR ai_summary LIKE '%' || :query || '%' ESCAPE '\'
               OR ai_tags LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY published_at DESC
        LIMIT :limit
        """
    )
    fun observeFeed(categories: List<String>, query: String, limit: Int): Flow<List<NewsEntity>>

    /**
     * Articolele din feed-ul Firestore aflate în cache (fără știrile locale și fără copiile din favorite).
     * 0 cu un cursor de sincronizare salvat = cache golit (ex. migrare distructivă) → resincronizare completă.
     */
    @Query("SELECT COUNT(*) FROM news WHERE category != :localCategory AND is_bookmarked = 0")
    suspend fun countFeedArticles(localCategory: String): Int

    /** Șterge articolele nesalvate din [category] care nu sunt în [keepIds] (știrile locale vechi). */
    @Query("DELETE FROM news WHERE category = :category AND is_bookmarked = 0 AND id NOT IN (:keepIds)")
    suspend fun deleteStaleInCategory(category: String, keepIds: List<String>)

    /** Toate articolele salvate (favorite). Flow reactiv. */
    @Query("SELECT * FROM news WHERE is_bookmarked = 1 ORDER BY published_at DESC")
    fun getBookmarkedArticles(): Flow<List<NewsEntity>>

    /** Un articol după ID (one-shot). */
    @Query("SELECT * FROM news WHERE id = :articleId LIMIT 1")
    suspend fun getArticleById(articleId: String): NewsEntity?

    /** Articolele existente din lista dată (pentru a păstra stările locale la sincronizare). */
    @Query("SELECT * FROM news WHERE id IN (:ids)")
    suspend fun getArticlesByIds(ids: List<String>): List<NewsEntity>

    @Query("UPDATE news SET is_read = 1 WHERE id = :articleId")
    suspend fun markAsRead(articleId: String)

    /**
     * Resetează istoricul de citire: toate articolele redevin „necitite”.
     * @return numărul de articole resetate
     */
    @Query("UPDATE news SET is_read = 0 WHERE is_read = 1")
    suspend fun resetReadHistory(): Int

    /** Setează explicit starea de favorit (valoare exactă, sincronizată și cu serverul). */
    @Query("UPDATE news SET is_bookmarked = :bookmarked WHERE id = :articleId")
    suspend fun setBookmarked(articleId: String, bookmarked: Boolean)

    /**
     * Votul utilizatorului (true = like, false = dislike, null = fără vot) + actualizarea optimistă
     * a contoarelor comunității cu [upDelta] / [downDelta] (-1 / 0 / +1).
     */
    @Query(
        """
        UPDATE news SET user_vote = :vote,
            upvotes = MAX(0, upvotes + :upDelta),
            downvotes = MAX(0, downvotes + :downDelta)
        WHERE id = :articleId
        """
    )
    suspend fun applyVote(articleId: String, vote: Boolean?, upDelta: Int, downDelta: Int)

    /**
     * „Trending azi”: știrile publicate după [since] (ISO 8601 UTC), cele mai votate primele,
     * filtrate după [query] la fel ca [observeFeed].
     */
    @Query(
        """
        SELECT * FROM news
        WHERE published_at >= :since AND upvotes > 0
          AND (:query = ''
               OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR ai_title LIKE '%' || :query || '%' ESCAPE '\'
               OR summary LIKE '%' || :query || '%' ESCAPE '\'
               OR ai_summary LIKE '%' || :query || '%' ESCAPE '\'
               OR ai_tags LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY upvotes DESC, published_at DESC
        LIMIT :limit
        """
    )
    fun observeTrending(since: String, query: String, limit: Int): Flow<List<NewsEntity>>

    /**
     * Sentimentul comunității pe piață: știrile cu impact UP / DOWN pe care cititorii le-au
     * validat (upvotes > downvotes), numărate pe pereche și direcție.
     */
    @Query(
        """
        SELECT affected_currency AS pair, market_sentiment AS sentiment, COUNT(*) AS count FROM news
        WHERE affected_currency IS NOT NULL AND market_sentiment IN ('UP', 'DOWN')
            AND upvotes > downvotes AND published_at >= :since
        GROUP BY affected_currency, market_sentiment
        """
    )
    fun observeCommunitySentiment(since: String): Flow<List<SentimentCount>>

    @Query("UPDATE news SET deep_dive = :deepDive WHERE id = :articleId")
    suspend fun setDeepDive(articleId: String, deepDive: String)

    /**
     * Cea mai recentă știre cu impact valutar clar (UP / DOWN) — bannerul Market Pulse.
     * Calculată local din cache, fără citiri suplimentare din Firestore.
     */
    @Query(
        """
        SELECT * FROM news
        WHERE affected_currency IS NOT NULL AND market_sentiment IN ('UP', 'DOWN')
        ORDER BY published_at DESC
        LIMIT 1
        """
    )
    fun observeLatestMarketEvent(): Flow<NewsEntity?>

    /** Știrile cu impact UP / DOWN pentru o pereche (punctele-știre de pe grafic), cele mai noi primele. */
    @Query(
        """
        SELECT * FROM news
        WHERE affected_currency = :pair AND market_sentiment IN ('UP', 'DOWN')
        ORDER BY published_at DESC
        LIMIT :limit
        """
    )
    suspend fun getMarketEvents(pair: String, limit: Int): List<NewsEntity>

    /**
     * Șterge articolele din cache nesincronizate de la [olderThan] încoace (`cached_at` se
     * actualizează la fiecare sincronizare). NU șterge favoritele.
     */
    @Query("DELETE FROM news WHERE cached_at < :olderThan AND is_bookmarked = 0")
    suspend fun clearOldCache(olderThan: Long)

    /** Șterge tot cache-ul (la deconectare: datele aparțin contului care pleacă). */
    @Query("DELETE FROM news")
    suspend fun clearAll()
}

/** Rând agregat pentru [NewsDao.observeCommunitySentiment]. */
data class SentimentCount(val pair: String, val sentiment: String, val count: Int)
