package ro.newsfilter.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.newsfilter.app.core.auth.GoogleSignInHelper
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.AuthResult
import ro.newsfilter.app.domain.repository.AuthRepository
import javax.inject.Inject

/** Modul formularului: autentificare sau creare cont. */
enum class AuthMode { LOGIN, REGISTER }

/** Câmpurile formularului (pentru validarea „la părăsirea câmpului”). */
enum class AuthField { NAME, EMAIL, PASSWORD }

/** Starea de pornire a `AuthActivity`: verificăm dacă există deja o sesiune validă. */
enum class StartupState { CHECKING, ALREADY_LOGGED_IN, NEEDS_AUTH }

/** Evenimente unice trimise către `AuthActivity`. */
sealed interface AuthEvent {
    /** Autentificare reușită: Activity-ul afișează [welcomeMessage] și deschide `MainActivity`. */
    data class Authenticated(val welcomeMessage: String) : AuthEvent
}

/**
 * Starea formularului de autentificare.
 *
 * Validarea în timp real: eroarea unui câmp devine vizibilă după ce utilizatorul
 * a părăsit câmpul ([touched]) sau a apăsat butonul principal ([submitAttempted]);
 * din acel moment, mesajul se actualizează la fiecare tastă.
 */
data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val touched: Set<AuthField> = emptySet(),
    val submitAttempted: Boolean = false,
    val isSubmitting: Boolean = false,
    val isGoogleLoading: Boolean = false,
    val errorMessage: String? = null
) {
    private fun visible(field: AuthField) = submitAttempted || field in touched

    val nameError: String?
        get() = if (mode == AuthMode.REGISTER && visible(AuthField.NAME)) AuthValidator.validateName(name) else null
    val emailError: String?
        get() = if (visible(AuthField.EMAIL)) AuthValidator.validateEmail(email) else null
    val passwordError: String?
        get() = if (visible(AuthField.PASSWORD)) AuthValidator.validatePassword(password) else null

    /** Formularul este valid (indiferent dacă erorile sunt deja afișate). */
    val isFormValid: Boolean
        get() = AuthValidator.validateEmail(email) == null &&
            AuthValidator.validatePassword(password) == null &&
            (mode == AuthMode.LOGIN || AuthValidator.validateName(name) == null)

    val isBusy: Boolean get() = isSubmitting || isGoogleLoading
}

/**
 * ViewModel-ul ecranului de autentificare (`AuthActivity` → `AuthScreen`).
 *
 * Responsabilități:
 * - verifică la pornire dacă există o sesiune salvată în DataStore ([startupState]);
 * - ține starea formularului și validează câmpurile (email cu `Patterns.EMAIL_ADDRESS`,
 *   parolă ≥ 6 caractere, nume obligatoriu la creare cont);
 * - trimite cererile la [AuthRepository] (Firebase Authentication; fără răspuns nu există acces);
 * - coordonează „Continuă cu Google”: selectorul Credential Manager (pornit din Activity)
 *   întoarce token-ul ID, verificat apoi de Firebase;
 * - emite [AuthEvent.Authenticated] o singură dată, la succes.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _startupState = MutableStateFlow(StartupState.CHECKING)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

    /** Tema aleasă în Setări se aplică și ecranului de autentificare. */
    val isDarkTheme: StateFlow<Boolean> = preferencesRepository.userPreferences
        .map { it.isDarkTheme }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Verificare unică a sesiunii: există sesiune Firebase salvată → direct la MainActivity.
        // Dacă Firebase o invalidează ulterior, `SessionManager` încheie sesiunea automat.
        viewModelScope.launch {
            val prefs = preferencesRepository.userPreferences.first()
            _startupState.value = if (prefs.isLoggedIn && prefs.userEmail.isNotBlank()) {
                StartupState.ALREADY_LOGGED_IN
            } else {
                StartupState.NEEDS_AUTH
            }
        }
    }

    // ---------------- Formular ----------------

    fun onModeChange(mode: AuthMode) {
        if (_uiState.value.mode == mode) return
        _uiState.update {
            it.copy(mode = mode, submitAttempted = false, touched = emptySet(), errorMessage = null)
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, errorMessage = null) }

    fun onEmailChange(value: String) =
        _uiState.update { it.copy(email = value.replace(" ", ""), errorMessage = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }

    fun onTogglePasswordVisibility() = _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }

    /** Câmpul a pierdut focusul după ce a fost editat → afișăm validarea lui. */
    fun onFieldBlur(field: AuthField) = _uiState.update { it.copy(touched = it.touched + field) }

    /** Trimiterea formularului: validare completă, apoi apel către repository. */
    fun submit() {
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update { it.copy(submitAttempted = true, errorMessage = null) }
        if (!state.isFormValid) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            val result = when (state.mode) {
                AuthMode.LOGIN -> authRepository.login(state.email, state.password)
                AuthMode.REGISTER -> authRepository.register(state.name, state.email, state.password)
            }
            handleResult(result)
        }
    }

    // ---------------- Google (Credential Manager) ----------------

    /**
     * „Continuă cu Google”: [requestIdToken] afișează selectorul de conturi (are nevoie de
     * Activity, deci vine din `AuthActivity`), apoi token-ul ID este schimbat într-o sesiune Firebase.
     */
    fun signInWithGoogle(requestIdToken: suspend () -> GoogleSignInHelper.Result) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isGoogleLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = requestIdToken()) {
                is GoogleSignInHelper.Result.Success -> handleResult(authRepository.signInWithGoogle(result.idToken))
                GoogleSignInHelper.Result.Cancelled -> _uiState.update { it.copy(isGoogleLoading = false) }
                is GoogleSignInHelper.Result.Error -> _uiState.update {
                    it.copy(isGoogleLoading = false, errorMessage = result.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private suspend fun handleResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                _events.send(AuthEvent.Authenticated("Bine ai venit, ${result.user.firstName}!"))
                // Păstrăm butoanele în starea „se încarcă” până la închiderea Activity-ului.
            }
            is AuthResult.Failure -> _uiState.update {
                it.copy(isSubmitting = false, isGoogleLoading = false, errorMessage = result.message)
            }
        }
    }
}
