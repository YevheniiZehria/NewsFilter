package ro.newsfilter.app.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.newsfilter.app.core.util.ArticleIntents
import ro.newsfilter.app.core.util.PublishedDate
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.ui.components.ArticleImage
import ro.newsfilter.app.ui.components.CategoryPill
import ro.newsfilter.app.ui.components.ErrorView
import ro.newsfilter.app.ui.components.ReadTimeLabel

/**
 * Ecranul de detaliu, în stil „revistă”:
 *  - imagine Hero sus (sub bara de status, edge-to-edge), cu bara plutitoare de
 *    butoane circulare translucide: „Înapoi” (stânga) și „Distribuie” (dreapta);
 *  - pastila de categorie, titlu mare, rând de metadate (sursă • timp de citire);
 *  - rezumatul AI cu spațiere aerisită (lineHeight 26.sp);
 *  - CTA principal „Citește articolul original” (sursa în engleză) → Chrome Custom Tabs (performanță
 *    nativă Chrome, cookie-urile utilizatorului, fără WebView propriu), cu fallback
 *    la ACTION_VIEW dacă nu există un browser compatibil;
 *  - bară de acțiuni jos: Like / Dislike (feedback instant + vot salvat în Firestore, votul
 *    e reținut în Room) și Salvează.
 *
 * Insets: ecranul nu are BottomNavBar. Conținutul trece intenționat pe sub bara
 * de status (imaginea), butonul „Înapoi” folosește `statusBarsPadding()`, iar bara
 * de acțiuni folosește `navigationBarsPadding()`. Scaffold-ul are
 * `contentWindowInsets = 0`, ca să nu aplice încă o dată aceste insets.
 *
 * @param onBackClick Callback la apăsarea butonului de înapoi
 * @param viewModel DetailViewModel injectat prin Hilt
 */
@Composable
fun DetailScreen(
    onBackClick: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isAudioPlaying by viewModel.isAudioPlaying.collectAsStateWithLifecycle()
    val article = uiState.article
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (article != null) {
                DetailActionBar(
                    isBookmarked = article.isBookmarked,
                    feedbackPositive = uiState.feedbackPositive,
                    isFeedbackSent = uiState.isFeedbackSent,
                    onLike = { viewModel.vote(isPositive = true) },
                    onDislike = { viewModel.vote(isPositive = false) },
                    onBookmark = viewModel::toggleBookmark
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.error != null -> {
                    ErrorView(
                        message = uiState.error ?: "Eroare necunoscută",
                        onRetry = onBackClick,
                        retryLabel = "Înapoi",
                        retryIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        modifier = Modifier.statusBarsPadding()
                    )
                }

                article != null -> ArticleContent(
                    article = article,
                    deepDive = uiState.deepDive,
                    isDeepDiveLoading = uiState.isDeepDiveLoading,
                    deepDiveError = uiState.deepDiveError,
                    onRequestDeepDive = viewModel::requestDeepDive
                )
            }

            // Bara plutitoare: „Înapoi” + „Distribuie” deasupra imaginii
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FloatingIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = "Înapoi",
                    onClick = onBackClick
                )
                if (article != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloatingIconButton(
                            icon = if (isAudioPlaying) Icons.Filled.Stop else Icons.Filled.Headphones,
                            description = if (isAudioPlaying) "Oprește briefingul audio" else "Ascultă briefingul audio",
                            onClick = viewModel::toggleAudio
                        )
                        FloatingIconButton(
                            icon = Icons.Filled.Share,
                            description = "Distribuie articolul",
                            onClick = { ArticleIntents.share(context, article.displayTitle, article.articleUrl) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleContent(
    article: NewsArticle,
    deepDive: String?,
    isDeepDiveLoading: Boolean,
    deepDiveError: String?,
    onRequestDeepDive: () -> Unit
) {
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ArticleImage(
            imageUrl = article.imageUrl,
            categorySlug = article.category,
            emoji = article.categoryEmoji,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            fallbackEmojiSize = 72.sp
        )

        // Conținutul „urcă” peste imagine, cu colțuri rotunjite sus (efect de foaie)
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = -28.dp.toPx() }
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                CategoryPill(
                    categorySlug = article.category,
                    label = article.categoryLabel,
                    emoji = article.categoryEmoji
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = article.displayTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Metadate: sursă • timp de citire
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = article.source,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "  •  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReadTimeLabel(minutes = article.readTimeMinutes, suffix = "min de lectură")
                }

                // Data și ora exactă a publicării (ex. „Vineri, 27 noiembrie 2026, ora 09:14”).
                PublishedDate.long(article.publishedAt)?.let { published ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Publicat: $published",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Voturile comunității (afișate de la 3 voturi în sus).
                article.usefulPercent?.let { percent ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "👍 $percent% dintre cititori consideră această știre utilă",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(20.dp))

                // Smart Digest (Gemini, în jobul de sincronizare): ideile cheie înaintea articolului complet.
                if (article.aiBullets.isNotEmpty() || article.aiTags.isNotEmpty()) {
                    SmartDigestCard(bullets = article.aiBullets, tags = article.aiTags)
                    Spacer(modifier = Modifier.height(20.dp))
                }

                val hasAiSummary = article.isAiProcessed
                val displaySummary = article.displaySummary
                val isLocal = article.category == Category.LOCAL.slug
                // Știrile locale fără aprofundare au fost rezumate extractiv (fără Gemini).
                val summaryLabel = when {
                    !hasAiSummary -> "TEXT ORIGINAL (EN) · rezumatul în română se generează"
                    isLocal && article.deepDive.isNullOrBlank() -> "REZUMAT AUTOMAT"
                    else -> "REZUMAT AI"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasAiSummary) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = summaryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasAiSummary) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = displaySummary,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (!article.fullContent.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    var isFullArticleExpanded by remember(article.id) { mutableStateOf(true) }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isFullArticleExpanded = !isFullArticleExpanded }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Articol complet",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = if (isFullArticleExpanded) {
                                        Icons.Filled.KeyboardArrowUp
                                    } else {
                                        Icons.Filled.KeyboardArrowDown
                                    },
                                    contentDescription = if (isFullArticleExpanded) {
                                        "Restrânge articolul complet"
                                    } else {
                                        "Extinde articolul complet"
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AnimatedVisibility(
                                visible = isFullArticleExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        bottom = 16.dp
                                    )
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = article.fullContent,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Știrile locale: aprofundarea există doar dacă a generat-o Gemini pe dispozitiv (nu din Firestore).
                if (!isLocal || deepDive != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    DeepDiveSection(
                        deepDive = deepDive,
                        isLoading = isDeepDiveLoading,
                        error = deepDiveError,
                        onRequest = onRequestDeepDive
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        ArticleIntents.openInBrowser(context, article.articleUrl, toolbarColor)
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(text = "Citește articolul original", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    // Știrile locale (Google News) sunt deja în română; restul vin din feed-urile BBC.
                    text = if (article.category == Category.LOCAL.slug) {
                        "Sursa originală · ${article.source.ifBlank { "Google News" }}"
                    } else {
                        "Sursa originală, în engleză · ${article.source.ifBlank { "BBC" }}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** Buton circular translucid care rămâne lizibil peste orice imagine. */
@Composable
private fun FloatingIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.38f),
            contentColor = Color.White
        )
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * Bara de acțiuni de jos: „Îmi place” / „Nu-mi place” (feedback de relevanță)
 * și „Salvează”. Starea se schimbă instantaneu (culoare animată + mic „pop”); aceeași apăsare
 * retrage votul, celălalt buton îl schimbă.
 */
@Composable
private fun DetailActionBar(
    isBookmarked: Boolean,
    feedbackPositive: Boolean?,
    isFeedbackSent: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onBookmark: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionPill(
                    icon = if (feedbackPositive == true) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    description = "Îmi place",
                    selected = feedbackPositive == true,
                    enabled = true,
                    selectedColor = MaterialTheme.colorScheme.primary,
                    onClick = onLike
                )
                ActionPill(
                    icon = if (feedbackPositive == false) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    description = "Nu-mi place",
                    selected = feedbackPositive == false,
                    enabled = true,
                    selectedColor = MaterialTheme.colorScheme.error,
                    onClick = onDislike
                )

                AnimatedContent(
                    targetState = isFeedbackSent,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "feedback_text",
                    modifier = Modifier.weight(1f)
                ) { sent ->
                    Text(
                        text = if (sent) "Mulțumim pentru feedback!" else "Relevant pentru tine?",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                ActionPill(
                    icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    description = if (isBookmarked) "Elimină din salvate" else "Salvează articolul",
                    label = if (isBookmarked) "Salvat" else "Salvează",
                    selected = isBookmarked,
                    enabled = true,
                    selectedColor = MaterialTheme.colorScheme.primary,
                    onClick = onBookmark
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    label: String? = null
) {
    val container by animateColorAsState(
        targetValue = if (selected) selectedColor.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "pill_container"
    )
    val content by animateColorAsState(
        targetValue = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "pill_content"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pill_scale"
    )

    Surface(
        onClick = onClick,
        enabled = enabled || selected,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = description
                stateDescription = if (selected) "Selectat" else "Neselectat"
            }
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = if (label != null) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            if (label != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Smart Digest: 2-3 idei cheie (TL;DR) și etichetele de subiect, generate de Gemini în jobul de sincronizare
 * la ingestie. Afișat înaintea rezumatului, ca cititorul să prindă esențialul în câteva secunde.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartDigestCard(bullets: List<String>, tags: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PE SCURT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            bullets.forEach { bullet ->
                Spacer(modifier = Modifier.height(10.dp))
                Row {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = bullet,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * „Aprofundează subiectul” (pre-generat de Gemini, citit din Firestore): context, de ce contează, ce urmează.
 * Generat la cerere (o singură dată pe server), apoi păstrat local.
 */
@Composable
private fun DeepDiveSection(
    deepDive: String?,
    isLoading: Boolean,
    error: String?,
    onRequest: () -> Unit
) {
    AnimatedContent(
        targetState = deepDive,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "deepDive"
    ) { text ->
        if (text != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.TravelExplore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "APROFUNDARE AI",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onRequest,
                    enabled = !isLoading,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Se analizează subiectul…")
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.TravelExplore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Aprofundează subiectul")
                    }
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
