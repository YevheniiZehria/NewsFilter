package ro.newsfilter.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Body pentru POST /auth/login și POST /users (cont nou cu email + parolă).
 * Parola este trimisă doar prin HTTPS (în producție) și este hash-uită în
 * PostgreSQL cu `crypt()` + bcrypt (extensia pgcrypto) — nu se stochează în clar.
 */
@JsonClass(generateAdapter = true)
data class AuthRequestDto(
    @Json(name = "email") val email: String,
    @Json(name = "password") val password: String,
    @Json(name = "name") val name: String = ""
)

/**
 * Body pentru POST /auth/google: access token-ul Clerk obținut prin OAuth + PKCE.
 * n8n îl verifică direct la Clerk (`/oauth/userinfo`) — aplicația nu trimite email-ul.
 */
@JsonClass(generateAdapter = true)
data class GoogleAuthRequestDto(
    @Json(name = "access_token") val accessToken: String
)

/** Profilul utilizatorului (rândul din tabela `users`). */
@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: String,
    @Json(name = "email") val email: String,
    @Json(name = "name") val name: String,
    @Json(name = "auth_provider") val authProvider: String
)

/**
 * Răspunsul unei autentificări reușite: token-ul de sesiune (opac, 256 biți) emis de n8n
 * și profilul. În baza de date ajunge doar hash-ul SHA-256 al token-ului.
 */
@JsonClass(generateAdapter = true)
data class AuthResponseDto(
    @Json(name = "token") val token: String,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "user") val user: UserDto
)
