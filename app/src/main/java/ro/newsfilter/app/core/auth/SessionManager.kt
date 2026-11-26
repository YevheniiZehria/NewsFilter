package ro.newsfilter.app.core.auth

import android.util.Log
import kotlinx.coroutines.runBlocking
import ro.newsfilter.app.data.local.dao.NewsDao
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sursa unică pentru sesiunea utilizatorului (token-ul emis de n8n).
 *
 * - Token-ul este păstrat **criptat** ([TokenCipher]) în DataStore și în clar doar în memorie,
 *   pentru [ro.newsfilter.app.core.network.AuthInterceptor].
 * - [endSession] șterge sesiunea ȘI datele personale din Room (favorite, istoric, voturi),
 *   ca un alt cont autentificat pe același telefon să nu le vadă. Favoritele revin din
 *   server la următoarea autentificare (GET /bookmarks).
 * - Activity-urile observă `isLoggedIn` din DataStore și navighează singure.
 */
@Singleton
class SessionManager @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val newsDao: NewsDao,
    private val cipher: TokenCipher
) {
    @Volatile private var cachedToken: String? = null
    @Volatile private var loaded = false
    private val lock = Any()

    /**
     * Token-ul curent sau null. Apelat din thread-urile OkHttp; prima apelare citește
     * DataStore (blocant, dar niciodată pe thread-ul UI).
     */
    fun currentToken(): String? {
        if (!loaded) {
            synchronized(lock) {
                if (!loaded) {
                    cachedToken = runBlocking { preferences.encryptedSessionToken() }?.let(cipher::decrypt)
                    loaded = true
                }
            }
        }
        return cachedToken
    }

    /** Salvează sesiunea nouă. Aruncă excepție dacă Keystore-ul nu poate cripta token-ul. */
    suspend fun startSession(user: User, token: String) {
        val encrypted = cipher.encrypt(token)
        preferences.saveSession(user.email, user.name, user.provider.key, encrypted)
        synchronized(lock) {
            cachedToken = token
            loaded = true
        }
    }

    /** Deconectare locală: sesiune + date personale din cache. Idempotentă. */
    suspend fun endSession() {
        synchronized(lock) {
            cachedToken = null
            loaded = true
        }
        preferences.clearSession()
        newsDao.clearAll()
    }

    /**
     * Serverul a respins [rejectedToken] (401): sesiune revocată, expirată sau cont șters.
     * Dacă între timp s-a pornit o sesiune nouă, nu o atingem.
     */
    fun onUnauthorized(rejectedToken: String) {
        if (currentToken() != rejectedToken) return
        Log.w(TAG, "Sesiunea a fost respinsă de server (401) — deconectare")
        runBlocking { endSession() }
    }

    private companion object {
        const val TAG = "NewsFilterAuth"
    }
}
