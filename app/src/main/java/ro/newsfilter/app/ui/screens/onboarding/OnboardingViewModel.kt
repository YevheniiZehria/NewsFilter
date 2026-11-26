package ro.newsfilter.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.Category
import javax.inject.Inject

/**
 * Starea UI pentru ecranul de Onboarding.
 */
data class OnboardingUiState(
    val selectedCategories: Set<String> = emptySet(),
    val defaultMinutes: Int = 5,
    val isSaving: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel pentru Onboarding.
 * Gestionează selecția de categorii și timpul implicit,
 * apoi le persistă în DataStore.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    val availableCategories = Category.selectable

    /**
     * Comută selecția unei categorii (multi-select).
     */
    fun toggleCategory(slug: String) {
        _uiState.update { current ->
            val updated = current.selectedCategories.toMutableSet()
            if (updated.contains(slug)) {
                updated.remove(slug)
            } else {
                updated.add(slug)
            }
            current.copy(selectedCategories = updated, error = null)
        }
    }

    /**
     * Setează timpul implicit de lectură (2, 5 sau 15 minute).
     */
    fun setDefaultMinutes(minutes: Int) {
        _uiState.update { it.copy(defaultMinutes = minutes) }
    }

    /**
     * Salvează preferințele și marchează onboarding-ul ca finalizat.
     * @param onComplete Callback apelat după salvare cu succes
     */
    fun completeOnboarding(onComplete: () -> Unit) {
        val selected = _uiState.value.selectedCategories
        if (selected.isEmpty()) {
            _uiState.update { it.copy(error = "Selectează cel puțin o categorie!") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                preferencesRepository.updateSelectedCategories(selected.toList())
                preferencesRepository.updateDefaultMinutes(_uiState.value.defaultMinutes)
                preferencesRepository.setOnboardingCompleted()
                onComplete()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = "Eroare la salvare: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}

