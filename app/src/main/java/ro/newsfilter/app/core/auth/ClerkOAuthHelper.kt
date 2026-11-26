package ro.newsfilter.app.core.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ro.newsfilter.app.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Autentificare reală cu Google prin **Clerk OAuth 2.0 + PKCE (S256)**.
 *
 * Flux:
 * 1. [startOAuth] generează `code_verifier`, `code_challenge` și `state`, apoi
 *    deschide pagina Clerk Hosted OAuth în Chrome Custom Tabs;
 * 2. Utilizatorul se autentifică cu contul său real Google în Clerk;
 * 3. Clerk redirecționează către `newsfilter://oauth-callback?code=...&state=...`;
 * 4. [handleCallback] verifică `state` și schimbă `code` pe `access_token` la `/oauth/token`;
 * 5. Token-ul este trimis la n8n (POST /auth/google), care află identitatea direct de la
 *    Clerk și emite sesiunea aplicației.
 */
@Singleton
class ClerkOAuthHelper @Inject constructor(
    @ApplicationContext context: Context
) {

    sealed interface Result {
        /** Access token-ul Clerk, trimis apoi la POST /auth/google pentru verificare pe server. */
        data class Success(val accessToken: String) : Result
        data class Error(val message: String) : Result
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Verifier-ul și state-ul PKCE sunt salvate pe disc, nu în memorie: Android poate
    // opri procesul cât timp utilizatorul este în browser, iar la întoarcere le pierdeam.
    private val pendingPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isConfigured: Boolean
        get() = BuildConfig.CLERK_CLIENT_ID.isNotBlank() && BuildConfig.CLERK_DOMAIN.isNotBlank()

    fun startOAuth(activity: Activity): Boolean {
        if (!isConfigured) return false

        val verifier = generateRandomUrlSafe(48)
        val challenge = sha256Base64Url(verifier)
        val state = generateRandomUrlSafe(24)

        // commit() (sincron): datele trebuie să fie pe disc înainte să deschidem browserul.
        pendingPrefs.edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .commit()

        val authUri = Uri.Builder()
            .scheme("https")
            .authority(BuildConfig.CLERK_DOMAIN)
            .appendPath("oauth")
            .appendPath("authorize")
            .appendQueryParameter("client_id", BuildConfig.CLERK_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid profile email")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        return try {
            CustomTabsIntent.Builder().build().launchUrl(activity, authUri)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, authUri))
                true
            } catch (e: Exception) {
                Log.e(TAG, "Nu s-a putut deschide browserul pentru Clerk OAuth", e)
                false
            }
        }
    }

    fun isOAuthCallback(uri: Uri?): Boolean =
        uri != null && uri.scheme == CALLBACK_SCHEME && uri.host == CALLBACK_HOST

    suspend fun handleCallback(uri: Uri): Result = withContext(Dispatchers.IO) {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val desc = uri.getQueryParameter("error_description").orEmpty()
            return@withContext Result.Error(
                desc.ifBlank { "Autentificarea Google a fost anulată sau respinsă ($error)." }
            )
        }

        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val verifier = pendingPrefs.getString(KEY_VERIFIER, null)
        val expectedState = pendingPrefs.getString(KEY_STATE, null)
        val createdAt = pendingPrefs.getLong(KEY_CREATED_AT, 0L)

        // Folosire unică: ștergem imediat, indiferent de rezultat.
        pendingPrefs.edit().clear().commit()

        if (verifier.isNullOrBlank() || expectedState.isNullOrBlank()) {
            return@withContext Result.Error("Sesiunea de autentificare Google a expirat. Încearcă din nou.")
        }
        if (System.currentTimeMillis() - createdAt > PENDING_MAX_AGE_MS) {
            return@withContext Result.Error("Sesiunea de autentificare Google a expirat. Încearcă din nou.")
        }
        if (returnedState != expectedState) {
            return@withContext Result.Error("Validarea de securitate OAuth (state) a eșuat.")
        }
        if (code.isNullOrBlank()) {
            return@withContext Result.Error("Răspuns OAuth invalid de la Clerk.")
        }

        try {
            val tokenBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", BuildConfig.CLERK_CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val tokenRequest = Request.Builder()
                .url("https://${BuildConfig.CLERK_DOMAIN}/oauth/token")
                .post(tokenBody)
                .build()

            val tokenJson = httpClient.newCall(tokenRequest).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Eroare /oauth/token (${resp.code}): $raw")
                    return@withContext Result.Error("Nu s-a putut obține token-ul de autentificare.")
                }
                JSONObject(raw)
            }

            val accessToken = tokenJson.optString("access_token")
            if (accessToken.isBlank()) {
                return@withContext Result.Error("Token-ul Clerk lipsește din răspuns.")
            }

            // Identitatea (email, nume) NU se citește aici: n8n verifică token-ul direct la
            // Clerk (/oauth/userinfo), ca serverul să nu depindă de ce afirmă aplicația.
            Result.Success(accessToken)
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la finalizarea Clerk OAuth", e)
            Result.Error("Eroare de rețea la conectarea cu Clerk.")
        }
    }

    private fun generateRandomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val TAG = "ClerkOAuth"
        const val CALLBACK_SCHEME = "newsfilter"
        const val CALLBACK_HOST = "oauth-callback"
        const val REDIRECT_URI = "$CALLBACK_SCHEME://$CALLBACK_HOST"

        const val PREFS_NAME = "clerk_oauth_pending"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_STATE = "state"
        const val KEY_CREATED_AT = "created_at"
        const val PENDING_MAX_AGE_MS = 10 * 60 * 1000L // 10 minute
    }
}
