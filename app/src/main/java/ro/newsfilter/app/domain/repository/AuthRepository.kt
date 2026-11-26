package ro.newsfilter.app.domain.repository

import ro.newsfilter.app.domain.model.AuthResult

/**
 * Contractul pentru autentificare și gestionarea sesiunii.
 *
 * Implementarea ([ro.newsfilter.app.data.repository.AuthRepositoryImpl]) folosește Firebase
 * Authentication: Firebase verifică credențialele și gestionează sesiunea; fără răspuns valid
 * de la Firebase nu există acces.
 */
interface AuthRepository {

    /** Autentificare cu email și parolă. */
    suspend fun login(email: String, password: String): AuthResult

    /** Creare cont nou cu email și parolă (profilul este salvat în `users/{uid}`). */
    suspend fun register(name: String, email: String, password: String): AuthResult

    /** Autentificare Google: [idToken] (obținut prin Credential Manager) este verificat de Firebase. */
    suspend fun signInWithGoogle(idToken: String): AuthResult

    /** Deconectare din Firebase + ștergerea sesiunii și a datelor personale locale. */
    suspend fun logout()
}
