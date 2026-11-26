package ro.newsfilter.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import ro.newsfilter.app.core.auth.SessionManager

/**
 * Adaugă `Authorization: Bearer <token>` la cererile către n8n și încheie sesiunea locală
 * când serverul răspunde 401 la o cerere care purta token-ul (sesiune revocată / expirată /
 * cont șters) — `MainActivity` revine automat la ecranul de autentificare.
 *
 * Endpoint-urile de autentificare sunt excluse: acolo 401 înseamnă „credențiale greșite”.
 */
class AuthInterceptor(private val sessionManager: SessionManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val isAuthEndpoint = AUTH_PATHS.any { path.endsWith(it) }
        val token = if (isAuthEndpoint) null else sessionManager.currentToken()

        if (token == null) return chain.proceed(request)

        val response = chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
        if (response.code == 401) {
            sessionManager.onUnauthorized(token)
        }
        return response
    }

    private companion object {
        val AUTH_PATHS = listOf("/auth/login", "/auth/google", "/users")
    }
}
