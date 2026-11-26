package ro.newsfilter.app.domain.model

/**
 * Utilizatorul autentificat (profilul Firebase, oglindit în `users/{uid}` din Firestore).
 *
 * @property email Email-ul (identificator unic, în litere mici)
 * @property name Numele afișat în aplicație (ex. în salutul din Feed)
 * @property provider Metoda de autentificare folosită
 */
data class User(
    val email: String,
    val name: String,
    val provider: AuthProvider
) {
    /** Prenumele, folosit în salutul personalizat („Bună seara, Ana”). */
    val firstName: String
        get() = name.trim().substringBefore(' ').ifBlank { email.substringBefore('@') }
}

/** Metodele de autentificare suportate; [key] este valoarea salvată în DataStore / Firestore. */
enum class AuthProvider(val key: String) {
    EMAIL("email"),
    GOOGLE("google");

    companion object {
        fun fromKey(key: String): AuthProvider = entries.find { it.key == key } ?: EMAIL
    }
}

/** Rezultatul unei operații de autentificare (doar prin Firebase, fără mod offline). */
sealed interface AuthResult {
    data class Success(val user: User) : AuthResult
    data class Failure(val message: String) : AuthResult
}
