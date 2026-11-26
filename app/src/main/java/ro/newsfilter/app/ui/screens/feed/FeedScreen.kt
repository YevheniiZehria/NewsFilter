package ro.newsfilter.app.ui.screens.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.newsfilter.app.core.sensor.ShakeToRefreshEffect
import ro.newsfilter.app.core.util.ArticleIntents
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.ui.components.ArticleActionsSheet
import ro.newsfilter.app.ui.components.CompactNewsCard
import ro.newsfilter.app.ui.components.ErrorView
import ro.newsfilter.app.ui.components.HeroNewsCard
import ro.newsfilter.app.ui.components.LoadingShimmer
import ro.newsfilter.app.ui.components.OfflineBanner
import ro.newsfilter.app.ui.components.ScreenHeader
import ro.newsfilter.app.ui.components.StatusView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ecranul principal „Pentru tine”, în stil editorial.
 *
 * Structură (de sus în jos, totul într-un singur LazyColumn care se derulează):
 *  1. Antet minimalist: salut în funcție de oră + data curentă în română;
 *  2. Selectorul de buget de timp (pastilă 2 / 5 / 15 min);
 *  3. Rezumatul bugetului: badge „3 articole • 4 din 5 min” + bară de progres;
 *  4. Primul articol ca card Hero, restul ca carduri compacte.
 *
 * Evenimente UI:
 *  - atingere pe card → detaliu; apăsare lungă → [ArticleActionsSheet] (meniu contextual);
 *  - meniul „⋮” din antet: reîmprospătare, resetarea istoricului de citire, deconectare;
 *  - scuturarea telefonului ([ShakeToRefreshEffect]) → pachet nou + vibrație + Snackbar;
 *  - pull-to-refresh.
 *
 * Insets: aplicăm o singură dată `statusBarsPadding()` pe rădăcină; partea de jos
 * e deja tratată de Scaffold-ul rădăcină (BottomNavBar). Nu mai există un Scaffold
 * imbricat în acest ecran.
 *
 * @param onArticleClick Callback la click pe o știre (navighează la detaliu)
 * @param viewModel FeedViewModel injectat prin Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onArticleClick: (String) -> Unit,
    viewModel: FeedViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    // Salutul se recalculează la fiecare revenire în aplicație (ex. dimineața → seara).
    var header by remember { mutableStateOf(greetingAndDate()) }
    LifecycleResumeEffect(Unit) {
        header = greetingAndDate()
        onPauseOrDispose { }
    }
    val (greeting, today) = header
    val title = if (uiState.userFirstName.isNotBlank()) "$greeting, ${uiState.userFirstName}" else greeting

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    var menuArticle by remember { mutableStateOf<NewsArticle?>(null) }
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    // Mesajele de confirmare din ViewModel (Channel → afișate o singură dată)
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            // Un mesaj nou îl înlocuiește pe cel afișat (showSnackbar suspendă până la închidere).
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // Senzor: scutură telefonul pentru un pachet nou (activ doar cât ecranul e vizibil)
    ShakeToRefreshEffect(enabled = uiState.shakeToRefreshEnabled) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.onShake()
    }

    val onCardLongClick: (NewsArticle) -> Unit = { menuArticle = it }

    val contentState = when {
        uiState.isLoading -> FeedContent.Loading
        uiState.error != null && uiState.articles.isEmpty() -> FeedContent.Error
        uiState.articles.isEmpty() -> FeedContent.Empty
        else -> FeedContent.List
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OfflineBanner(isOffline = uiState.isOffline)

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.loadNews(isRefresh = true) },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // === 1. Antet: salut + dată ===
                    item(key = "header") {
                        ScreenHeader(
                            title = title,
                            subtitle = today,
                            actions = {
                                IconButton(onClick = { viewModel.loadNews(isRefresh = true) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Reîmprospătează",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FeedOverflowMenu(
                                    onRefresh = { viewModel.loadNews(isRefresh = true) },
                                    isServerRefreshRunning = uiState.isServerRefreshRunning,
                                    onForceServerRefresh = viewModel::forceServerRefresh,
                                    onResetHistory = viewModel::resetReadHistory,
                                    onLogout = viewModel::logout
                                )
                            }
                        )
                    }

                    // === 2. Conținut: cele mai recente știri ===
                    when (contentState) {
                        FeedContent.Loading -> item(key = "loading") {
                            LoadingShimmer(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem()
                            )
                        }

                        FeedContent.Error -> item(key = "error") {
                            ErrorView(
                                message = uiState.error ?: "Eroare necunoscută",
                                isOffline = uiState.isOffline,
                                onRetry = { viewModel.loadNews() },
                                modifier = Modifier
                                    .fillParentMaxHeight(0.65f)
                                    .animateItem()
                            )
                        }

                        FeedContent.Empty -> item(key = "empty") {
                            StatusView(
                                icon = Icons.Outlined.DoneAll,
                                title = "Ești la zi!",
                                message = "Nu am găsit știri noi pentru preferințele tale. " +
                                    "Adaugă mai multe domenii din Setări sau reîmprospătează.",
                                actionLabel = "Reîmprospătează",
                                actionIcon = Icons.Filled.Refresh,
                                onAction = { viewModel.loadNews() },
                                modifier = Modifier
                                    .fillParentMaxHeight(0.65f)
                                    .animateItem()
                            )
                        }

                        FeedContent.List -> itemsIndexed(
                            items = uiState.articles,
                            key = { _, article -> article.id }
                        ) { index, article ->
                            val itemModifier = Modifier
                                .padding(horizontal = 16.dp)
                                .animateItem()
                            if (index == 0) {
                                Column(modifier = itemModifier) {
                                    Text(
                                        text = "Cele mai recente știri",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                                    )
                                    HeroNewsCard(
                                        article = article,
                                        onArticleClick = onArticleClick,
                                        onBookmarkClick = viewModel::toggleBookmark,
                                        onLongClick = onCardLongClick
                                    )
                                }
                            } else {
                                Column(modifier = itemModifier) {
                                    if (index == 1) {
                                        Text(
                                            text = "Mai multe știri recente",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp)
                                        )
                                    }
                                    CompactNewsCard(
                                        article = article,
                                        onArticleClick = onArticleClick,
                                        onBookmarkClick = viewModel::toggleBookmark,
                                        onLongClick = onCardLongClick
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = contentState == FeedContent.List && !audioState.isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = viewModel::playAudioPack,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null
                    )
                },
                text = {
                    Text("Ascultă știrile")
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // Meniul contextual (apăsare lungă pe card)
    menuArticle?.let { article ->
        ArticleActionsSheet(
            article = article,
            onDismiss = { menuArticle = null },
            onToggleBookmark = viewModel::toggleBookmarkFromMenu,
            onMarkAsRead = { viewModel.markAsReadFromMenu(it.id) },
            onShare = { ArticleIntents.share(context, it.title, it.articleUrl) },
            onOpenInBrowser = { ArticleIntents.openInBrowser(context, it.articleUrl, toolbarColor) }
        )
    }
}

/**
 * Meniul „⋮” din antetul Feed-ului (overflow menu): IconButton + DropdownMenu.
 */
@Composable
private fun FeedOverflowMenu(
    onRefresh: () -> Unit,
    isServerRefreshRunning: Boolean,
    onForceServerRefresh: () -> Unit,
    onResetHistory: () -> Unit,
    onLogout: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Mai multe opțiuni",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            DropdownMenuItem(
                text = { Text("Reîmprospătează știrile") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRefresh()
                }
            )
            // App → n8n: rulează acum workflow-ul de ingestie (RSS → scraping → rezumate AI).
            DropdownMenuItem(
                text = {
                    Text(if (isServerRefreshRunning) "Se pornește actualizarea…" else "Forțează actualizarea surselor")
                },
                leadingIcon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                enabled = !isServerRefreshRunning,
                onClick = {
                    expanded = false
                    onForceServerRefresh()
                }
            )
            DropdownMenuItem(
                text = { Text("Resetează istoricul de citire") },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = {
                    expanded = false
                    onResetHistory()
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Deconectare", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    expanded = false
                    onLogout()
                }
            )
        }
    }
}

private enum class FeedContent { Loading, Error, Empty, List }

/**
 * Salutul în funcție de ora curentă și data formatată în română
 * (ex. „Bună dimineața” / „Luni, 17 noiembrie”).
 */
private fun greetingAndDate(): Pair<String, String> {
    val now = LocalDateTime.now()
    val greeting = when (now.hour) {
        in 5..11 -> "Bună dimineața"
        in 12..17 -> "Bună ziua"
        else -> "Bună seara"
    }
    val romanian = Locale.forLanguageTag("ro-RO")
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", romanian))
        .replaceFirstChar { it.titlecase(romanian) }
    return greeting to date
}
