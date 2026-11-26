package ro.newsfilter.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extensie care creează un singur DataStore la nivel de Context.
 * Decizie tehnică: preferencesDataStore trebuie definit ca extensie
 * top-level pe Context (impus de API-ul DataStore).
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

/**
 * Modelul preferințelor utilizatorului.
 *
 * @property selectedCategories Categoriile selectate (slug-uri separate prin virgulă)
 * @property defaultMinutes Timpul implicit de lectură (2, 5 sau 15 minute)
 * @property isDarkTheme Temă întunecată activată
 * @property onboardingCompleted Dacă onboarding-ul a fost parcurs
 * @property dailyNotificationEnabled Notificarea zilnică activată
 * @property notificationHour Ora notificării zilnice (0-23)
 * @property notificationMinute Minutul notificării zilnice (0-59)
 * @property shakeToRefreshEnabled „Scutură pentru știri noi” (senzorul accelerometru)
 * @property isLoggedIn Există o sesiune activă (flag + token de sesiune criptat salvat)
 * @property userEmail Email-ul utilizatorului autentificat
 * @property userName Numele afișat al utilizatorului autentificat
 * @property authProvider Metoda de autentificare: "email" sau "google"
 */
data class UserPreferences(
    val selectedCategories: List<String> = emptyList(),
    val defaultMinutes: Int = 5,
    val isDarkTheme: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val dailyNotificationEnabled: Boolean = false,
    val notificationHour: Int = 8,
    val notificationMinute: Int = 0,
    val shakeToRefreshEnabled: Boolean = true,
    val isLoggedIn: Boolean = false,
    val userEmail: String = "",
    val userName: String = "",
    val authProvider: String = ""
)

/**
 * Repository pentru preferințele utilizatorului, implementat cu DataStore.
 *
 * Decizie tehnică: DataStore Preferences e soluția modernă Android
 * care înlocuiește SharedPreferences. E type-safe, asincron, nu blochează
 * UI-ul și emite modificări prin Flow reactiv.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SELECTED_CATEGORIES = stringPreferencesKey("selected_categories")
        val DEFAULT_MINUTES = intPreferencesKey("default_minutes")
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DAILY_NOTIFICATION_ENABLED = booleanPreferencesKey("daily_notification_enabled")
        val NOTIFICATION_HOUR = intPreferencesKey("notification_hour")
        val NOTIFICATION_MINUTE = intPreferencesKey("notification_minute")
        val SHAKE_TO_REFRESH = booleanPreferencesKey("shake_to_refresh")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_NAME = stringPreferencesKey("user_name")
        val AUTH_PROVIDER = stringPreferencesKey("auth_provider")

        /** Token-ul de sesiune emis de n8n, criptat cu Android Keystore (vezi TokenCipher). */
        val SESSION_TOKEN = stringPreferencesKey("session_token_enc")
    }

    /**
     * Flow reactiv cu preferințele curente.
     * Orice modificare emite automat o nouă valoare.
     */
    val userPreferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            selectedCategories = prefs[Keys.SELECTED_CATEGORIES]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?: emptyList(),
            defaultMinutes = prefs[Keys.DEFAULT_MINUTES] ?: 5,
            isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            dailyNotificationEnabled = prefs[Keys.DAILY_NOTIFICATION_ENABLED] ?: false,
            notificationHour = prefs[Keys.NOTIFICATION_HOUR] ?: 8,
            notificationMinute = prefs[Keys.NOTIFICATION_MINUTE] ?: 0,
            shakeToRefreshEnabled = prefs[Keys.SHAKE_TO_REFRESH] ?: true,
            // Sesiunile vechi (fără token, din versiunea cu login offline) nu mai sunt valide.
            isLoggedIn = (prefs[Keys.IS_LOGGED_IN] ?: false) && prefs[Keys.SESSION_TOKEN] != null,
            userEmail = prefs[Keys.USER_EMAIL].orEmpty(),
            userName = prefs[Keys.USER_NAME].orEmpty(),
            authProvider = prefs[Keys.AUTH_PROVIDER].orEmpty()
        )
    }

    /** Salvează categoriile selectate ca string separat prin virgulă. */
    suspend fun updateSelectedCategories(categories: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_CATEGORIES] = categories.joinToString(",")
        }
    }

    /** Salvează timpul implicit de lectură. */
    suspend fun updateDefaultMinutes(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_MINUTES] = minutes
        }
    }

    /** Comută tema luminoasă/întunecată. */
    suspend fun updateDarkTheme(isDark: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_DARK_THEME] = isDark
        }
    }

    /** Marchează onboarding-ul ca finalizat. */
    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = true
        }
    }

    /** Activează/dezactivează notificarea zilnică. */
    suspend fun updateDailyNotification(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DAILY_NOTIFICATION_ENABLED] = enabled
        }
    }

    /** Setează ora notificării zilnice. */
    suspend fun updateNotificationTime(hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFICATION_HOUR] = hour
            prefs[Keys.NOTIFICATION_MINUTE] = minute
        }
    }

    /** Activează/dezactivează „Scutură pentru știri noi”. */
    suspend fun updateShakeToRefresh(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SHAKE_TO_REFRESH] = enabled
        }
    }

    // ===================== Sesiunea utilizatorului =====================

    /**
     * Salvează sesiunea utilizatorului autentificat. Folosită doar de `SessionManager`,
     * care criptează token-ul înainte. Odată scrisă, `AuthActivity` deschide `MainActivity`.
     */
    suspend fun saveSession(email: String, name: String, provider: String, encryptedToken: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = true
            prefs[Keys.USER_EMAIL] = email
            prefs[Keys.USER_NAME] = name
            prefs[Keys.AUTH_PROVIDER] = provider
            prefs[Keys.SESSION_TOKEN] = encryptedToken
        }
    }

    /** Token-ul de sesiune criptat, sau null dacă nu există sesiune. */
    suspend fun encryptedSessionToken(): String? = context.dataStore.data.first()[Keys.SESSION_TOKEN]

    /**
     * Șterge sesiunea (deconectare). Preferințele de lectură și onboarding-ul rămân
     * pe dispozitiv. `MainActivity` observă `isLoggedIn = false` și revine la `AuthActivity`.
     */
    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = false
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.AUTH_PROVIDER)
            prefs.remove(Keys.SESSION_TOKEN)
            // Curățenie: hash-urile de parolă ale vechiului login offline („local_account_*”).
            prefs.asMap().keys
                .filter { it.name.startsWith("local_account_") }
                .forEach { prefs.remove(it) }
        }
    }
}
