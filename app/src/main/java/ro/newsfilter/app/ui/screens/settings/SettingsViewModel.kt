package ro.newsfilter.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.CancellationException
import ro.newsfilter.app.core.notifications.MarketAlerts
import ro.newsfilter.app.data.preferences.UserPreferences
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.core.sensor.ShakeDetector
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.domain.repository.AuthRepository
import ro.newsfilter.app.worker.NotificationScheduler
import javax.inject.Inject

/**
 * ViewModel pentru ecranul de Setări (și pentru tema aplicației în `MainActivity`).
 * Citește / scrie preferințele din DataStore, reprogramează notificarea zilnică
 * prin [NotificationScheduler] și gestionează deconectarea.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** True dacă dispozitivul are accelerometru (pentru „Scutură pentru știri noi”). */
    val isShakeSupported: Boolean = ShakeDetector.isAvailable(context)

    /**
     * Preferințele așa cum au fost citite din DataStore, sau `null` cât timp prima
     * citire nu s-a terminat. Folosit la pornire pentru a afișa un ecran de
     * încărcare în loc de valorile implicite (care ar arăta scurt Onboarding-ul).
     */
    val loadedPreferences: StateFlow<UserPreferences?> = preferencesRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /** Preferințele curente, cu valori implicite până la prima citire din DataStore. */
    val preferences: StateFlow<UserPreferences> = loadedPreferences
        .map { it ?: UserPreferences() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = loadedPreferences.value ?: UserPreferences()
        )

    val allCategories = Category.entries

    fun toggleCategory(slug: String) {
        val currentCategories = preferences.value.selectedCategories.toMutableSet()
        if (currentCategories.contains(slug)) {
            if (currentCategories.size > 1) { // Păstrăm cel puțin o categorie
                currentCategories.remove(slug)
            }
        } else {
            currentCategories.add(slug)
        }
        viewModelScope.launch {
            preferencesRepository.updateSelectedCategories(currentCategories.toList())
        }
    }

    fun setDefaultMinutes(minutes: Int) {
        viewModelScope.launch {
            preferencesRepository.updateDefaultMinutes(minutes)
        }
    }

    fun setDarkTheme(isDark: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateDarkTheme(isDark)
        }
    }

    fun setDailyNotification(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateDailyNotification(enabled)
            if (enabled) {
                val hour = preferences.value.notificationHour
                val minute = preferences.value.notificationMinute
                NotificationScheduler.scheduleDailyNotification(context, hour, minute)
            } else {
                NotificationScheduler.cancelDailyNotification(context)
            }
        }
    }

    /**
     * Alertele de piață: preferința locală + abonarea la topicul FCM `market_alerts`.
     * Dacă FCM nu răspunde (ex. offline), preferința revine la valoarea anterioară.
     */
    fun setMarketAlerts(enabled: Boolean, onFailure: () -> Unit = {}) {
        viewModelScope.launch {
            preferencesRepository.updateMarketAlerts(enabled)
            try {
                MarketAlerts.setSubscribed(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "Abonarea la alertele de piață a eșuat: ${e.message}")
                preferencesRepository.updateMarketAlerts(!enabled)
                onFailure()
            }
        }
    }

    /**
     * Salvează noua oră a notificării zilnice și, dacă notificarea e activă,
     * reprogramează imediat WorkManager-ul pentru ora aleasă.
     *
     * Folosim valorile primite ca parametru (nu `preferences.value`), deoarece
     * Flow-ul din DataStore poate încă să nu fi emis noua valoare.
     */
    fun setNotificationTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            preferencesRepository.updateNotificationTime(hour, minute)
            if (preferences.value.dailyNotificationEnabled) {
                NotificationScheduler.scheduleDailyNotification(context, hour, minute)
            }
        }
    }

    fun setShakeToRefresh(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateShakeToRefresh(enabled)
        }
    }

    /** Salvează locația pentru știrile locale; Feed-ul le descarcă automat la schimbare. */
    fun setLocalNews(city: String, country: String) {
        viewModelScope.launch {
            preferencesRepository.updateLocalNews(city.trim(), country.trim())
        }
    }

    /** Deconectare — `MainActivity` observă sesiunea și revine la `AuthActivity`. */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}
