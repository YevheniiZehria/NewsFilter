package ro.newsfilter.app.core.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ro.newsfilter.app.data.local.dao.NewsDao
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sursa unică pentru sesiunea utilizatorului, construită peste **Firebase Authentication**.
 *
 * - Firebase păstrează și reîmprospătează singur credențialele (token-ul ID expiră la o oră);
 *   aplicația nu mai gestionează niciun token.
 * - În DataStore păstrăm doar o oglindă a profilului (UID, email, nume, metodă), pe care
 *   Activity-urile o observă (`isLoggedIn`) pentru a naviga singure.
 * - [endSession] deconectează din Firebase ȘI șterge datele personale din Room (favorite,
 *   istoric, voturi), ca un alt cont de pe același telefon să nu le vadă. Favoritele revin din
 *   Firestore (`users/{uid}/bookmarks`) la următoarea autentificare.
 * - [start] (apelat din `NewsFilterApp`) urmărește starea Firebase: dacă Firebase nu mai are
 *   utilizator (cont șters / dezactivat, date șterse) dar DataStore încă îl are, sesiunea se
 *   încheie automat și UI-ul revine la autentificare.
 */
@Singleton
class SessionManager @Inject constructor(
    private val auth: FirebaseAuth,
    private val preferences: UserPreferencesRepository,
    private val newsDao: NewsDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** UID-ul utilizatorului Firebase curent, sau null. */
    val currentUid: String? get() = auth.currentUser?.uid

    /** Pornește sincronizarea Firebase → DataStore. Idempotentă. */
    fun start() {
        if (started) return
        started = true
        // Firebase restaurează utilizatorul sincron, de pe disc. Fără utilizator → nicio dată
        // personală nu trebuie să rămână (inclusiv cache-ul unei sesiuni n8n din versiunea veche).
        if (auth.currentUser == null) scope.launch { clearLocalData() }
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            scope.launch {
                val savedUid = preferences.firebaseUid()
                if (user == null && savedUid != null) {
                    Log.w(TAG, "Sesiunea Firebase nu mai există — deconectare")
                    clearLocalData()
                } else if (user != null && savedUid != null && savedUid != user.uid) {
                    // Alt cont decât cel salvat (nu ar trebui să se întâmple) → pornim curat.
                    clearLocalData()
                }
            }
        }
    }

    /** Salvează profilul după o autentificare Firebase reușită. */
    suspend fun startSession(uid: String, user: User) {
        preferences.saveSession(uid, user.email, user.name, user.provider.key)
    }

    /** Deconectare: Firebase + sesiune locală + date personale din cache. Idempotentă. */
    suspend fun endSession() {
        auth.signOut()
        clearLocalData()
    }

    private suspend fun clearLocalData() {
        preferences.clearSession()
        newsDao.clearAll()
    }

    private companion object {
        const val TAG = "NewsFilterAuth"
    }
}
