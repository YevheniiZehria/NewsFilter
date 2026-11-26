package ro.newsfilter.app.data.repository

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthResult as FirebaseAuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import ro.newsfilter.app.core.auth.SessionManager
import ro.newsfilter.app.domain.model.AuthProvider
import ro.newsfilter.app.domain.model.AuthResult
import ro.newsfilter.app.domain.model.User
import ro.newsfilter.app.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementarea autentificării cu **Firebase Authentication** (email + parolă și Google).
 *
 * Flux de date:
 * 1. Firebase verifică credențialele (parola nu este stocată de aplicație; pentru Google,
 *    token-ul ID obținut prin Credential Manager este verificat de Firebase).
 * 2. La succes, profilul este scris (best effort) în Firestore, la `users/{uid}`, iar
 *    [SessionManager] salvează oglinda locală a sesiunii → UI-ul deschide `MainActivity`.
 * 3. Accesul la datele personale din Firestore este protejat de regulile de securitate
 *    (`request.auth.uid == uid`), nu de aplicație.
 * 4. Orice eroare devine un mesaj specific în română ([AuthResult.Failure]); fără răspuns
 *    valid de la Firebase nu există acces (fără mod offline).
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val sessionManager: SessionManager
) : AuthRepository {

    override suspend fun login(email: String, password: String): AuthResult = authenticate(AuthProvider.EMAIL) {
        auth.signInWithEmailAndPassword(email.trim().lowercase(), password).await()
    }

    override suspend fun register(name: String, email: String, password: String): AuthResult =
        authenticate(AuthProvider.EMAIL, displayName = name.trim()) {
            auth.createUserWithEmailAndPassword(email.trim().lowercase(), password).await().also { result ->
                // Contul există deja; un eșec la numele afișat nu trebuie să anuleze înregistrarea.
                try {
                    result.user?.updateProfile(userProfileChangeRequest { displayName = name.trim() })?.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Numele afișat nu a putut fi salvat: ${e.message}")
                }
            }
        }

    override suspend fun signInWithGoogle(idToken: String): AuthResult = authenticate(AuthProvider.GOOGLE) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun logout() {
        sessionManager.endSession()
    }

    // ------------------------------------------------------------------

    private suspend fun authenticate(
        provider: AuthProvider,
        displayName: String? = null,
        call: suspend () -> FirebaseAuthResult
    ): AuthResult {
        val firebaseUser = try {
            call().user ?: return AuthResult.Failure("Răspuns invalid de la Firebase.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Autentificare eșuată", e)
            return AuthResult.Failure(e.toAuthMessage())
        }

        val email = firebaseUser.email.orEmpty()
        val user = User(
            email = email,
            name = (displayName ?: firebaseUser.displayName)?.takeIf { it.isNotBlank() } ?: nameFromEmail(email),
            provider = provider
        )
        saveProfile(firebaseUser, user, isNew = displayName != null)
        sessionManager.startSession(firebaseUser.uid, user)
        Log.d(TAG, "Sesiune Firebase pornită (${provider.key})")
        return AuthResult.Success(user)
    }

    /** Profilul din `users/{uid}`; o eroare aici nu blochează autentificarea. */
    private suspend fun saveProfile(firebaseUser: FirebaseUser, user: User, isNew: Boolean) {
        val data = buildMap<String, Any> {
            put("name", user.name)
            put("email", user.email)
            put("authProvider", user.provider.key)
            put("lastLoginAt", FieldValue.serverTimestamp())
            if (isNew) put("createdAt", FieldValue.serverTimestamp())
        }
        try {
            // Offline, `await()` nu se termină până la confirmarea serverului → limită de timp,
            // ca autentificarea să nu rămână blocată (scrierea rămâne oricum în coada Firestore).
            val confirmed = withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
                firestore.collection("users").document(firebaseUser.uid).set(data, SetOptions.merge()).await()
                true
            }
            if (confirmed == null) Log.w(TAG, "Profilul nu a fost confirmat de server în timp util")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Profilul nu a putut fi salvat în Firestore: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "NewsFilterAuth"
        const val PROFILE_TIMEOUT_MS = 5_000L

        fun Exception.toAuthMessage(): String = when (this) {
            is FirebaseAuthWeakPasswordException -> "Parola este prea slabă (minim 6 caractere)."
            is FirebaseAuthUserCollisionException -> "Există deja un cont cu acest email. Autentifică-te."
            is FirebaseAuthInvalidUserException -> when (errorCode) {
                "ERROR_USER_DISABLED" -> "Acest cont a fost dezactivat."
                else -> "Email sau parolă incorectă."
            }
            is FirebaseAuthInvalidCredentialsException -> when (errorCode) {
                "ERROR_INVALID_EMAIL" -> "Adresa de email nu este validă."
                else -> "Email sau parolă incorectă."
            }
            is FirebaseTooManyRequestsException -> "Prea multe încercări. Încearcă din nou peste câteva minute."
            is FirebaseNetworkException -> "Nu există conexiune la internet. Verifică rețeaua și încearcă din nou."
            else -> "Autentificarea a eșuat. Încearcă din nou."
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
