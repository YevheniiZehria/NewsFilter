package ro.newsfilter.app.ui

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ro.newsfilter.app.R
import ro.newsfilter.app.core.notifications.DeepLink
import ro.newsfilter.app.core.notifications.DeepLinks
import ro.newsfilter.app.ui.components.AudioMiniPlayer
import ro.newsfilter.app.ui.navigation.BottomNavBar
import ro.newsfilter.app.ui.navigation.NavGraph
import ro.newsfilter.app.ui.navigation.Routes
import ro.newsfilter.app.ui.screens.settings.SettingsViewModel
import ro.newsfilter.app.ui.theme.NewsFilterTheme

/**
 * Rădăcina UI pentru NewsFilter:
 * - Aplică tema luminoasă / întunecată conform setărilor utilizatorului
 * - Controlează vizibilitatea BottomNavBar (doar pe Feed, Bookmarks, Settings)
 * - Pornește automat cu Onboarding dacă aplicația este la prima rulare
 * - Gardă de sesiune: dacă utilizatorul nu (mai) este autentificat, apelează
 *   [onSessionEnded] → `MainActivity` revine la `AuthActivity`
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsFilterAppContent(
    onSessionEnded: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    audioPlayerViewModel: AudioPlayerViewModel = hiltViewModel()
) {
    val loadedPrefs by settingsViewModel.loadedPreferences.collectAsStateWithLifecycle()
    val audioState by audioPlayerViewModel.state.collectAsStateWithLifecycle()
    val prefs = loadedPrefs
    val currentOnSessionEnded by rememberUpdatedState(onSessionEnded)

    // Sesiunea a fost ștearsă (Deconectare) sau lipsește → înapoi la AuthActivity.
    if (prefs != null && !prefs.isLoggedIn) {
        LaunchedEffect(Unit) { currentOnSessionEnded() }
        NewsFilterTheme(darkTheme = prefs.isDarkTheme) {
            SplashLoading()
        }
        return
    }

    // Până la prima citire reală din DataStore afișăm un splash neutru, ca să nu
    // „clipească" Onboarding-ul pentru utilizatorii care l-au parcurs deja.
    if (prefs == null) {
        NewsFilterTheme(darkTheme = isSystemInDarkTheme()) {
            SplashLoading()
        }
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Alertele de piață (FCM): articol → Detaliu; pereche valutară → Feed (widget-ul e
    // selectat de FeedScreen). Așteptăm finalizarea Onboarding-ului înainte de a naviga.
    val pendingLink by DeepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink, prefs.onboardingCompleted) {
        val link = pendingLink ?: return@LaunchedEffect
        if (!prefs.onboardingCompleted) return@LaunchedEffect
        when (link) {
            is DeepLink.Article -> {
                navController.navigate(Routes.Detail.createRoute(link.articleId)) { launchSingleTop = true }
                DeepLinks.consume(link)
            }
            is DeepLink.MarketPair -> navController.navigate(Routes.Feed.route) {
                popUpTo(Routes.Feed.route)
                launchSingleTop = true
            }
        }
    }

    val shouldShowBottomBar = currentRoute in listOf(
        Routes.Feed.route,
        Routes.Bookmarks.route,
        Routes.Settings.route
    )

    SystemBarsAppearance(darkTheme = prefs.isDarkTheme)

    NewsFilterTheme(darkTheme = prefs.isDarkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // Fixăm destinația de start la prima valoare încărcată: când Onboarding-ul
            // se finalizează, navigarea spre Feed e făcută explicit din NavGraph, iar
            // schimbarea startDestination ar reconstrui inutil graful de navigare.
            val startDestination = rememberSaveable {
                if (prefs.onboardingCompleted) {
                    Routes.Feed.route
                } else {
                    Routes.Onboarding.route
                }
            }

            // Gestionarea insets-urilor (edge-to-edge):
            // - Scaffold-ul rădăcină NU aplică el însuși insets-urile de sistem
            //   (contentWindowInsets = 0). Fiecare ecran are propriul Scaffold +
            //   TopAppBar, care tratează singur bara de status.
            // - innerPadding conține doar înălțimea BottomNavBar (care include deja
            //   bara de navigare a sistemului). Prin consumeWindowInsets, Scaffold-urile
            //   ecranelor află că partea de jos e deja tratată și nu o mai adaugă încă
            //   o dată. Pe Detail/Onboarding (fără bară) padding-ul e 0, iar ecranul
            //   își aplică singur inset-ul barei de navigare.
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    Column {
                        AudioMiniPlayer(
                            state = audioState,
                            onPlayPause = audioPlayerViewModel::togglePlayPause,
                            onNext = audioPlayerViewModel::next,
                            onCycleSpeed = audioPlayerViewModel::cycleSpeed,
                            onCycleVoice = audioPlayerViewModel::cycleVoice,
                            onClose = audioPlayerViewModel::stop
                        )
                        AnimatedVisibility(
                            visible = shouldShowBottomBar,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                        ) {
                            BottomNavBar(navController = navController)
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                ) {
                    NavGraph(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }
    }
}

/**
 * Sincronizează culoarea iconițelor din bara de status / navigare cu tema aleasă
 * în aplicație (nu cu tema sistemului). Altfel, cu tema întunecată activată din
 * Setări pe un telefon în mod luminos, iconițele de status ar rămâne închise la
 * culoare pe fundal închis (contrast slab).
 */
@Composable
private fun SystemBarsAppearance(darkTheme: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    DisposableEffect(activity, darkTheme) {
        val style = SystemBarStyle.auto(
            lightScrim = AndroidColor.TRANSPARENT,
            darkScrim = AndroidColor.TRANSPARENT
        ) { darkTheme }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
}

/**
 * Ecran minimal de pornire afișat cât timp se citesc preferințele din DataStore.
 */
@Composable
private fun SplashLoading() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
            }
        }
    }
}
