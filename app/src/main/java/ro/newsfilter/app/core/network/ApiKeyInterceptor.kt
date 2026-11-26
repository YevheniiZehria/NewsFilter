package ro.newsfilter.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import ro.newsfilter.app.BuildConfig

/**
 * OkHttp Interceptor care adaugă automat header-ul de autentificare
 * la fiecare cerere către API-ul n8n.
 *
 * Decizie tehnică: Folosim un header custom "X-API-Key" în loc de
 * query parameter, pentru a nu expune cheia în loguri sau URL-uri.
 * Valoarea vine din BuildConfig, configurată central în build.gradle.kts.
 */
class ApiKeyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val authenticatedRequest = originalRequest.newBuilder()
            .header("X-API-Key", BuildConfig.N8N_API_KEY)
            .header("Accept", "application/json")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}

