package ro.newsfilter.app.ui.screens.auth

import android.util.Patterns

/**
 * Reguli de validare pentru formularul de autentificare.
 * Fiecare funcție întoarce mesajul de eroare (în română) sau `null` dacă valoarea e validă.
 * Sunt funcții pure, ușor de testat, folosite atât în timp real cât și la trimitere.
 */
object AuthValidator {

    const val MIN_PASSWORD_LENGTH = 6

    fun validateName(name: String): String? = when {
        name.isBlank() -> "Introdu numele tău."
        name.trim().length < 2 -> "Numele trebuie să aibă cel puțin 2 caractere."
        else -> null
    }

    fun validateEmail(email: String): String? = when {
        email.isBlank() -> "Introdu adresa de email."
        !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Adresa de email nu este validă."
        else -> null
    }

    fun validatePassword(password: String): String? = when {
        password.isEmpty() -> "Introdu parola."
        password.length < MIN_PASSWORD_LENGTH ->
            "Parola trebuie să aibă cel puțin $MIN_PASSWORD_LENGTH caractere."
        else -> null
    }
}
