package ro.newsfilter.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import ro.newsfilter.app.data.remote.dto.AuthRequestDto
import ro.newsfilter.app.data.remote.dto.AuthResponseDto
import ro.newsfilter.app.data.remote.dto.BookmarkRequestDto
import ro.newsfilter.app.data.remote.dto.DeepDiveRequestDto
import ro.newsfilter.app.data.remote.dto.DeepDiveResponseDto
import ro.newsfilter.app.data.remote.dto.FeedbackRequestDto
import ro.newsfilter.app.data.remote.dto.GoogleAuthRequestDto
import ro.newsfilter.app.data.remote.dto.NewsDto

/**
 * Interfața Retrofit pentru webhook-urile n8n (URL-uri relative la BuildConfig.N8N_BASE_URL).
 *
 * Toate cererile poartă `X-API-Key` ([ro.newsfilter.app.core.network.ApiKeyInterceptor]);
 * cele protejate poartă și `Authorization: Bearer <token>`
 * ([ro.newsfilter.app.core.network.AuthInterceptor]). Un 401 pe o cerere protejată
 * încheie sesiunea locală.
 *
 * Publice (doar cheie API):
 * - POST /auth/login, POST /users, POST /auth/google → `{ token, expires_at, user }`
 * Protejate (sesiune):
 * - POST /auth/logout, GET /news, GET|POST /bookmarks, POST /feedback, POST /deep-dive
 * - POST /refresh (workflow-ul de ingestie; 202 = pornit, 429 = pornit recent)
 */
interface NewsApiService {

    // ---------------- Autentificare ----------------

    /** 200 → sesiune nouă; 401 → credențiale greșite. */
    @POST("auth/login")
    suspend fun login(@Body request: AuthRequestDto): Response<AuthResponseDto>

    /** 200 → cont creat + sesiune; 409 → email deja folosit; 400 → date invalide. */
    @POST("users")
    suspend fun register(@Body request: AuthRequestDto): Response<AuthResponseDto>

    /** 200 → sesiune; 401 → token Clerk invalid; 409 → email folosit de un cont cu parolă. */
    @POST("auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequestDto): Response<AuthResponseDto>

    /** Revocă token-ul curent pe server. */
    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    // ---------------- Știri ----------------

    /**
     * Cele mai recente știri (max. 50) din categoriile cerute.
     *
     * @param categories Categorii separate prin virgulă (ex: "tech,sport")
     * @param maxMinutes Timpul de lectură ales (păstrat pentru compatibilitate)
     * @param exclude ID-uri deja citite, separate prin virgulă (listă plafonată în repository)
     */
    @GET("news")
    suspend fun getNews(
        @Query("categories") categories: String,
        @Query("maxMinutes") maxMinutes: Int,
        @Query("exclude") exclude: String? = null
    ): List<NewsDto>

    /** Feedback de relevanță (like/dislike), asociat contului. */
    @POST("feedback")
    suspend fun sendFeedback(@Body feedback: FeedbackRequestDto): Response<Unit>

    // ---------------- Acțiuni app → n8n ----------------

    /** Favoritele contului (pentru sincronizare după autentificare). */
    @GET("bookmarks")
    suspend fun getBookmarks(): List<NewsDto>

    @POST("bookmarks")
    suspend fun setBookmark(@Body request: BookmarkRequestDto): Response<Unit>

    /** Analiza AI „Aprofundează subiectul” (generată o dată, apoi servită din cache pe server). */
    @POST("deep-dive")
    suspend fun deepDive(@Body request: DeepDiveRequestDto): DeepDiveResponseDto

    /** „Forțează actualizarea”: pornește workflow-ul de ingestie (RSS → scraping → Gemini). */
    @POST("refresh")
    suspend fun forceRefresh(): Response<Unit>
}
