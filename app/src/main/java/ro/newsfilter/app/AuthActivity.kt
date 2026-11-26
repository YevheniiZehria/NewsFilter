package ro.newsfilter.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import ro.newsfilter.app.core.auth.ClerkOAuthHelper
import ro.newsfilter.app.ui.screens.auth.AuthEvent
import ro.newsfilter.app.ui.screens.auth.AuthScreen
import ro.newsfilter.app.ui.screens.auth.AuthViewModel
import ro.newsfilter.app.ui.screens.auth.StartupState
import ro.newsfilter.app.ui.theme.NewsFilterTheme
import javax.inject.Inject

/**
 * Activity-ul de intrare (`LAUNCHER`) — prima dintre cele două Activity-uri ale aplicației.
 *
 * Responsabilități:
 * 1. **Verificarea sesiunii**: la pornire, [AuthViewModel] citește DataStore;
 *    dacă există deja o sesiune validă, trecem imediat la [MainActivity].
 * 2. **Autentificarea**: găzduiește `AuthScreen` (email + parolă, cont nou,
 *    „Continuă cu Google”). Logica și validarea sunt în [AuthViewModel].
 * 3. **Sign in with Google**: fluxul Clerk OAuth + PKCE se deschide în Custom Tabs prin
 *    [ClerkOAuthHelper]; callback-ul `newsfilter://oauth-callback` revine aici, iar
 *    access token-ul este predat ViewModel-ului (verificat apoi de n8n).
 * 4. **Navigarea între Activity-uri**: la succes deschide [MainActivity] cu o
 *    tranziție fade și apelează `finish()`, ca butonul Back să nu revină aici.
 *
 * Flux: AuthActivity ──(sesiune salvată în DataStore)──► MainActivity
 *       MainActivity ──(Deconectare: sesiune ștearsă)──► AuthActivity
 */
@AndroidEntryPoint
class AuthActivity : ComponentActivity() {

    @Inject
    lateinit var clerkOAuthHelper: ClerkOAuthHelper

    private val viewModel: AuthViewModel by viewModels()
    private var isNavigating = false
    private var handlingOAuthCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        observeNavigation()
        // Doar la prima creare: după rotire, intent-ul de callback este livrat din nou,
        // dar verifier-ul PKCE a fost deja consumat (ar apărea o eroare falsă).
        if (savedInstanceState == null) handleOAuthIntent(intent)

        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            val startupState by viewModel.startupState.collectAsStateWithLifecycle()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            NewsFilterTheme(darkTheme = isDarkTheme) {
                if (startupState != StartupState.NEEDS_AUTH) {
                    // Verificăm sesiunea sau trecem deja spre MainActivity.
                    StartupSplash()
                } else {
                    AuthScreen(
                        state = uiState,
                        onModeChange = viewModel::onModeChange,
                        onNameChange = viewModel::onNameChange,
                        onEmailChange = viewModel::onEmailChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
                        onFieldBlur = viewModel::onFieldBlur,
                        onSubmit = viewModel::submit,
                        onGoogleClick = ::launchGoogleSignIn
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.uiState.value.isGoogleLoading && !handlingOAuthCallback) {
            viewModel.onGoogleSignInCancelled()
        }
    }

    /** Ascultă sesiunea existentă și evenimentele de autentificare reușită. */
    private fun observeNavigation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.startupState.collect { state ->
                        if (state == StartupState.ALREADY_LOGGED_IN) openMainActivity()
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is AuthEvent.Authenticated -> {
                                Toast.makeText(applicationContext, event.welcomeMessage, Toast.LENGTH_SHORT).show()
                                openMainActivity()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Pornește autentificarea reală cu Google prin Clerk OAuth 2.0 + PKCE. */
    private fun launchGoogleSignIn() {
        if (!clerkOAuthHelper.isConfigured) {
            viewModel.onGoogleSignInFailed("Autentificarea Clerk nu este configurată în local.properties.")
            return
        }
        viewModel.onGoogleSignInStarted()
        if (!clerkOAuthHelper.startOAuth(this)) {
            viewModel.onGoogleSignInFailed("Nu s-a putut deschide browserul pentru autentificarea Google.")
        }
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!clerkOAuthHelper.isOAuthCallback(uri)) return

        handlingOAuthCallback = true
        viewModel.onGoogleSignInStarted()
        lifecycleScope.launch {
            val result = clerkOAuthHelper.handleCallback(uri)
            handlingOAuthCallback = false
            viewModel.onClerkOAuthResult(result)
        }
    }

    /** Navigare explicită către a doua Activity, cu tranziție fade; AuthActivity se închide. */
    private fun openMainActivity() {
        if (isNavigating) return
        isNavigating = true
        val options = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        startActivity(Intent(this, MainActivity::class.java), options.toBundle())
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun StartupSplash() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
