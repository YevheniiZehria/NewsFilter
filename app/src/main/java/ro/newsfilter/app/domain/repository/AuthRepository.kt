package ro.newsfilter.app.domain.repository

import ro.newsfilter.app.domain.model.AuthResult

/**
 * Contractul pentru autentificare și gestionarea sesiunii.
 *
 * Implementarea ([ro.newsfilter.app.data.repository.AuthRepositoryImpl]) folosește exclusiv
 * backend-ul n8n + PostgreSQL: la succes, serverul emite un token de sesiune per utilizator,
 * păstrat criptat pe dispozitiv. Fără răspuns valid de la server nu există acces.
 */
interface AuthRepository {

    /** Autentificare cu email și parolă (POST /auth/login). */
    suspend fun login(email: String, password: String): AuthResult

    /** Creare cont nou cu email și parolă (POST /users). */
    suspend fun register(name: String, email: String, password: String): AuthResult

    /**
     * Autentificare Google: [clerkAccessToken] (obținut prin Clerk OAuth + PKCE) este trimis
     * la POST /auth/google, unde n8n îl verifică direct la Clerk.
     */
    suspend fun signInWithGoogle(clerkAccessToken: String): AuthResult

    /** Deconectare: revocă token-ul pe server (best effort) și șterge sesiunea locală. */
    suspend fun logout()
}
