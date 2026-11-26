package ro.newsfilter.app.core.network

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException

/**
 * Eroare cu un mesaj deja formulat pentru utilizator (în română), afișat ca atare.
 * Ex.: „Aprofundarea nu este încă disponibilă”, „Sesiunea a expirat”.
 */
class UserFacingException(message: String) : Exception(message)

/**
 * Traduce excepțiile Firebase / de rețea în mesaje clare pentru utilizator (în locul
 * textelor tehnice în engleză).
 */
fun Throwable.toUserMessage(): String = when (this) {
    is UserFacingException -> message.orEmpty()
    is FirebaseFirestoreException -> when (code) {
        Code.UNAVAILABLE -> "Nu se poate contacta serverul. Verifică conexiunea."
        Code.DEADLINE_EXCEEDED -> "Serverul nu a răspuns la timp. Încearcă din nou."
        Code.PERMISSION_DENIED, Code.UNAUTHENTICATED -> "Sesiunea a expirat. Autentifică-te din nou."
        Code.RESOURCE_EXHAUSTED -> "Limita zilnică a serverului a fost atinsă. Încearcă din nou mai târziu."
        Code.NOT_FOUND -> "Datele cerute nu există pe server."
        else -> "Eroare de server (${code.name}). Încearcă din nou."
    }
    is FirebaseNetworkException, is IOException -> "Nu se poate contacta serverul. Verifică conexiunea."
    else -> "A apărut o eroare neașteptată."
}
