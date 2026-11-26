package ro.newsfilter.app.core.network

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Traduce excepțiile de rețea în mesaje clare pentru utilizator (în locul textelor
 * tehnice de tip „Expected BEGIN_ARRAY but was STRING at path $”).
 */
fun Throwable.toUserMessage(): String = when (this) {
    is SocketTimeoutException -> "Serverul nu a răspuns la timp. Încearcă din nou."
    is UnknownHostException -> "Nu se poate contacta serverul. Verifică conexiunea."
    is JsonDataException, is JsonEncodingException -> "Răspuns invalid de la server."
    is HttpException -> workflowMessage() ?: when (code()) {
        401 -> "Sesiunea a expirat. Autentifică-te din nou."
        403 -> "Configurare invalidă: cheia API nu corespunde serverului."
        404 -> "Serviciul nu este activ (workflow-ul n8n este inactiv)."
        429 -> "Prea multe cereri. Încearcă din nou peste câteva minute."
        in 500..599 -> "Eroare de server (HTTP ${code()}). Încearcă din nou."
        else -> "Cererea a eșuat (HTTP ${code()})."
    }
    is IOException -> "Nu se poate contacta serverul. Verifică conexiunea."
    else -> "A apărut o eroare neașteptată."
}

/**
 * Mesajul trimis explicit de workflow-urile noastre (în română) pentru erorile „de aplicație”:
 * 429 (limitare), 502 / 503 (serviciul AI). Celelalte coduri pot veni din n8n însuși (texte
 * tehnice în engleză), așa că pentru ele păstrăm mesajele din aplicație.
 */
private fun HttpException.workflowMessage(): String? {
    if (code() !in setOf(429, 502, 503)) return null
    return try {
        response()?.errorBody()?.string()
            ?.let { JSONObject(it).optString("message") }
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
