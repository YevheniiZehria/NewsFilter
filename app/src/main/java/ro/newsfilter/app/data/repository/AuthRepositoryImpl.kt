package ro.newsfilter.app.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import ro.newsfilter.app.core.auth.SessionManager
import ro.newsfilter.app.core.network.toUserMessage
import ro.newsfilter.app.data.remote.NewsApiService
import ro.newsfilter.app.data.remote.dto.AuthRequestDto
import ro.newsfilter.app.data.remote.dto.AuthResponseDto
import ro.newsfilter.app.data.remote.dto.GoogleAuthRequestDto
import ro.newsfilter.app.domain.model.AuthProvider
import ro.newsfilter.app.domain.model.AuthResult
import ro.newsfilter.app.domain.model.User
import ro.newsfilter.app.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementarea autentificării: **strict, doar prin server**, cu sesiuni per utilizator.
 *
 * Flux de date:
 * 1. Cererea pleacă spre n8n (`/auth/login`, `/users` sau `/auth/google`). Pentru Google,
 *    aplicația trimite doar access token-ul Clerk; n8n află identitatea direct de la Clerk.
 * 2. La succes, n8n creează un rând în tabela `sessions` (doar hash-ul token-ului) și
 *    întoarce token-ul în clar o singură dată.
 * 3. [SessionManager] criptează token-ul cu Android Keystore și îl salvează; toate cererile
 *    ulterioare îl trimit ca `Authorization: Bearer`.
 * 4. Orice altceva decât 2xx (401, 403, 409, 404, 5xx, timeout, fără rețea, JSON invalid)
 *    blochează accesul cu un mesaj specific.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiService: NewsApiService,
    private val sessionManager: SessionManager
) : AuthRepository {

    override suspend fun login(email: String, password: String): AuthResult =
        authenticate(
            call = { apiService.login(AuthRequestDto(email.trim().lowercase(), password)) },
            messageFor = mapOf(401 to "Email sau parolă incorectă.")
        )

    override suspend fun register(name: String, email: String, password: String): AuthResult =
        authenticate(
            call = { apiService.register(AuthRequestDto(email.trim().lowercase(), password, name.trim())) },
            messageFor = mapOf(409 to "Există deja un cont cu acest email. Autentifică-te.")
        )

    override suspend fun signInWithGoogle(clerkAccessToken: String): AuthResult =
        authenticate(
            call = { apiService.loginWithGoogle(GoogleAuthRequestDto(clerkAccessToken)) },
            messageFor = mapOf(
                401 to "Autentificarea Google nu a putut fi verificată. Încearcă din nou.",
                409 to "Acest email are deja un cont cu parolă. Autentifică-te cu email și parolă."
            )
        )

    override suspend fun logout() {
        // Revocarea pe server este „best effort”: deconectarea locală are loc oricum,
        // chiar și offline (token-ul expiră singur pe server după 30 de zile).
        try {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { apiService.logout() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Revocarea sesiunii pe server a eșuat: ${e.message}")
        }
        sessionManager.endSession()
    }

    // ------------------------------------------------------------------

    /**
     * Execută apelul de autentificare și pornește sesiunea doar pe 2xx cu token valid.
     * [messageFor] conține mesajele pentru codurile cu semnificație specifică endpoint-ului.
     */
    private suspend fun authenticate(
        call: suspend () -> Response<AuthResponseDto>,
        messageFor: Map<Int, String>
    ): AuthResult {
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Autentificare eșuată (fără răspuns valid)", e)
            return AuthResult.Failure(e.toUserMessage())
        }

        val body = response.body()
        val code = response.code()
        if (!response.isSuccessful || body == null || body.token.isBlank()) {
            val message = messageFor[code] ?: when {
                response.isSuccessful -> "Răspuns invalid de la server."
                else -> httpMessage(code)
            }
            Log.e(TAG, "Autentificare eșuată (HTTP $code): $message")
            return AuthResult.Failure(message)
        }

        val user = User(
            email = body.user.email,
            name = body.user.name.ifBlank { nameFromEmail(body.user.email) },
            provider = AuthProvider.fromKey(body.user.authProvider)
        )
        return try {
            sessionManager.startSession(user, body.token)
            Log.d(TAG, "Sesiune pornită pentru ${user.email}")
            AuthResult.Success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keystore indisponibil / defect: GeneralSecurityException sau ProviderException.
            Log.e(TAG, "Token-ul de sesiune nu a putut fi criptat", e)
            AuthResult.Failure("Sesiunea nu a putut fi salvată în siguranță pe acest dispozitiv.")
        }
    }

    private companion object {
        const val TAG = "NewsFilterAuth"
        const val LOGOUT_TIMEOUT_MS = 5_000L

        fun httpMessage(code: Int): String = when (code) {
            400 -> "Datele trimise nu sunt valide."
            403 -> "Configurare invalidă: cheia API nu corespunde serverului."
            404 -> "Serviciul de autentificare nu este activ (workflow-ul n8n este inactiv)."
            in 500..599 -> "Eroare de server (HTTP $code). Încearcă din nou."
            else -> "Autentificarea a eșuat (HTTP $code)."
        }

        /** „ana.popescu@x.ro” → „Ana Popescu”. */
        fun nameFromEmail(email: String): String =
            email.substringBefore('@')
                .split('.', '_', '-')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
                .ifBlank { "Cititor" }
    }
}
