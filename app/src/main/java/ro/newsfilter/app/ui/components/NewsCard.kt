package ro.newsfilter.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import ro.newsfilter.app.domain.model.MarketSentiment
import ro.newsfilter.app.domain.model.NewsArticle

private val HeroShape = RoundedCornerShape(20.dp)
private val CompactShape = RoundedCornerShape(16.dp)
private val ThumbnailShape = RoundedCornerShape(14.dp)

/**
 * Cardul „Hero” — primul articol din pachet, pus în evidență ca pe prima
 * pagină a unei reviste:
 *  - imagine 16:9 sus, cu pastila de categorie translucidă peste ea;
 *  - titlu mare (max. 3 rânduri);
 *  - rând de metadate: sursă • timp de citire • pastila de piață + buton de salvare.
 *
 * Evenimente: atingere → detaliu; apăsare lungă → meniu contextual (cu vibrație).
 */
@Composable
fun HeroNewsCard(
    article: NewsArticle,
    onArticleClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((NewsArticle) -> Unit)? = null
) {
    var marketExpanded by rememberSaveable(article.id) { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .articleClickable(article, HeroShape, onArticleClick, onLongClick),
        shape = HeroShape,
        colors = CardDefaults.cardColors(containerColor = cardContainer(article)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            ArticleImage(
                imageUrl = article.imageUrl,
                categorySlug = article.category,
                emoji = article.categoryEmoji,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                fallbackEmojiSize = 56.sp
            )
            CategoryPill(
                categorySlug = article.category,
                label = article.categoryLabel,
                emoji = article.categoryEmoji,
                modifier = Modifier.padding(12.dp)
            )
        }

        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleLarge,
                color = titleColor(article),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = article.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        MetaRow(
            article = article,
            onBookmarkClick = onBookmarkClick,
            marketExpanded = marketExpanded,
            onMarketToggle = { marketExpanded = !marketExpanded },
            modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
        )
        MarketImpactNote(
            article = article,
            visible = marketExpanded,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
        )
    }
}

/**
 * Cardul compact, orizontal — pentru restul articolelor (scanare rapidă):
 *  - stânga: categorie • sursă, titlu (max. 2 rânduri), timp de citire + salvare;
 *  - dreapta: miniatură pătrată rotunjită de 88dp.
 *
 * Evenimente: atingere → detaliu; apăsare lungă (opțională) → meniu contextual.
 */
@Composable
fun CompactNewsCard(
    article: NewsArticle,
    onArticleClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((NewsArticle) -> Unit)? = null
) {
    var marketExpanded by rememberSaveable(article.id) { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .articleClickable(article, CompactShape, onArticleClick, onLongClick),
        shape = CompactShape,
        colors = CardDefaults.cardColors(containerColor = cardContainer(article)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Categorie • Sursă
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${article.categoryEmoji} ${article.categoryLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = categoryContentColor(article.category),
                        maxLines = 1
                    )
                    Text(
                        text = "  •  ${article.source}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor(article),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            ArticleImage(
                imageUrl = article.imageUrl,
                categorySlug = article.category,
                emoji = article.categoryEmoji,
                modifier = Modifier
                    .size(88.dp)
                    .clip(ThumbnailShape),
                fallbackEmojiSize = 32.sp
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReadTimeLabel(minutes = article.readTimeMinutes)
            MarketImpactPill(
                article = article,
                expanded = marketExpanded,
                onToggle = { marketExpanded = !marketExpanded },
                modifier = Modifier.padding(start = 10.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            BookmarkButton(article = article, onBookmarkClick = onBookmarkClick)
        }
        MarketImpactNote(
            article = article,
            visible = marketExpanded,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        )
    }
}

/**
 * Evenimente multiple pe card prin `combinedClickable`:
 *  - `onClick` → deschide detaliul articolului;
 *  - `onLongClick` → vibrație scurtă (`LocalHapticFeedback`) + meniul contextual.
 * Card-ul nu mai folosește varianta `Card(onClick)`, care suportă doar atingerea simplă.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.articleClickable(
    article: NewsArticle,
    shape: Shape,
    onArticleClick: (String) -> Unit,
    onLongClick: ((NewsArticle) -> Unit)?
): Modifier {
    val haptic = LocalHapticFeedback.current
    return this
        .clip(shape)
        .combinedClickable(
            onClickLabel = "Deschide articolul",
            onLongClickLabel = if (onLongClick != null) "Mai multe acțiuni" else null,
            onLongClick = onLongClick?.let { callback ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    callback(article)
                }
            },
            onClick = { onArticleClick(article.id) }
        )
}

/** Rândul de metadate al cardului Hero: sursă • timp de citire … salvare. */
@Composable
private fun MetaRow(
    article: NewsArticle,
    onBookmarkClick: (String) -> Unit,
    marketExpanded: Boolean,
    onMarketToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = article.source,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = "  •  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ReadTimeLabel(minutes = article.readTimeMinutes)
        MarketImpactPill(
            article = article,
            expanded = marketExpanded,
            onToggle = onMarketToggle,
            modifier = Modifier.padding(start = 10.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        BookmarkButton(article = article, onBookmarkClick = onBookmarkClick)
    }
}

/**
 * Pastila Market Pulse (ex. „EUR/RON ↑”), afișată doar dacă AI-ul a legat știrea de o
 * pereche valutară. Atingerea are propriul `clickable` (nu deschide articolul) și
 * extinde / restrânge nota de impact de sub card.
 */
@Composable
private fun MarketImpactPill(
    article: NewsArticle,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pair = article.affectedCurrency ?: return
    val sentiment = article.marketSentiment ?: MarketSentiment.NEUTRAL
    val color = sentimentColor(sentiment)
    Text(
        text = "$pair ${sentiment.arrow}",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (expanded) 0.22f else 0.12f))
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Ascunde impactul pe piață" else "Vezi impactul pe piață",
                enabled = !article.marketImpactNote.isNullOrBlank(),
                onClick = onToggle
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Nota AI de impact pe piață, afișată sub card după atingerea pastilei. */
@Composable
private fun MarketImpactNote(article: NewsArticle, visible: Boolean, modifier: Modifier = Modifier) {
    val note = article.marketImpactNote?.takeIf { article.affectedCurrency != null && it.isNotBlank() } ?: return
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Impact estimat de AI",
                style = MaterialTheme.typography.labelSmall,
                color = sentimentColor(article.marketSentiment)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Timp de citire discret: iconiță ceas + „X min”. */
@Composable
fun ReadTimeLabel(minutes: Int, modifier: Modifier = Modifier, suffix: String = "min") {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$minutes $suffix",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Buton de salvare cu țintă de atingere de 48dp (recomandarea Material). */
@Composable
private fun BookmarkButton(article: NewsArticle, onBookmarkClick: (String) -> Unit) {
    IconButton(onClick = { onBookmarkClick(article.id) }) {
        Icon(
            imageVector = if (article.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (article.isBookmarked) "Elimină din salvate" else "Salvează articolul",
            tint = if (article.isBookmarked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** Articolele deja citite sunt ușor „estompate”, ca să iasă în evidență cele noi. */
@Composable
private fun cardContainer(article: NewsArticle): Color =
    if (article.isRead) MaterialTheme.colorScheme.surfaceContainer
    else MaterialTheme.colorScheme.surfaceContainerLow

@Composable
private fun titleColor(article: NewsArticle): Color =
    if (article.isRead) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurface

/**
 * Imagine de articol încărcată cu Coil:
 *  - shimmer animat cât timp se descarcă;
 *  - crossfade la afișare;
 *  - fallback elegant (gradient în culoarea categoriei + emoji) dacă URL-ul
 *    lipsește sau descărcarea eșuează — cardurile nu rămân niciodată „goale”.
 *
 * Dimensiunea (înălțime / aspect ratio / formă) se dă prin [modifier].
 * Reutilizată în carduri și în ecranul de detaliu.
 */
@Composable
fun ArticleImage(
    imageUrl: String?,
    categorySlug: String,
    emoji: String,
    modifier: Modifier = Modifier,
    fallbackEmojiSize: TextUnit = 40.sp
) {
    if (imageUrl.isNullOrBlank()) {
        ImageFallback(categorySlug, emoji, fallbackEmojiSize, modifier)
        return
    }
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .build(),
        contentDescription = null, // decorativă: titlul articolului e citit separat
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(rememberShimmerBrush())
            )
        },
        error = {
            ImageFallback(categorySlug, emoji, fallbackEmojiSize, Modifier.fillMaxSize())
        }
    )
}

@Composable
private fun ImageFallback(
    categorySlug: String,
    emoji: String,
    emojiSize: TextUnit,
    modifier: Modifier
) {
    val accent = getCategoryColor(categorySlug)
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    lerp(surface, accent, 0.35f),
                    lerp(surface, accent, 0.10f)
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = emojiSize,
            modifier = Modifier.offset(y = 1.dp)
        )
    }
}
