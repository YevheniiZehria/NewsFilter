package ro.newsfilter.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Meniul contextual deschis prin apăsare lungă pe un card de știre.
 *
 * Patru acțiuni rapide, fără a deschide articolul:
 *  1. Salvează / Elimină din salvate (Room);
 *  2. Marchează ca citit (Room) — articolul dispare din pachetul curent;
 *  3. Distribuie articolul (`Intent.ACTION_SEND`);
 *  4. Deschide în browser (Chrome Custom Tabs).
 *
 * Sheet-ul se închide animat înainte de a executa acțiunea.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleActionsSheet(
    article: NewsArticle,
    onDismiss: () -> Unit,
    onToggleBookmark: (NewsArticle) -> Unit,
    onMarkAsRead: (NewsArticle) -> Unit,
    onShare: (NewsArticle) -> Unit,
    onOpenInBrowser: (NewsArticle) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /** Închide sheet-ul animat, apoi execută acțiunea aleasă. */
    fun runAndClose(action: (NewsArticle) -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action(article)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Antet: categorie • sursă + titlul articolului
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = "${article.categoryEmoji} ${article.categoryLabel}  •  ${article.source}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = article.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            SheetAction(
                icon = if (article.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                label = if (article.isBookmarked) "Elimină din salvate" else "Salvează",
                description = if (article.isBookmarked) {
                    "Scoate articolul din lista ta de lectură"
                } else {
                    "Păstrează articolul pentru mai târziu"
                },
                highlighted = article.isBookmarked,
                onClick = { runAndClose(onToggleBookmark) }
            )
            SheetAction(
                icon = Icons.Outlined.TaskAlt,
                label = "Marchează ca citit",
                description = "Îl scoate din pachetul de azi",
                onClick = { runAndClose(onMarkAsRead) }
            )
            SheetAction(
                icon = Icons.Outlined.Share,
                label = "Distribuie articolul",
                description = "Trimite link-ul prin orice aplicație",
                onClick = { runAndClose(onShare) }
            )
            SheetAction(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                label = "Deschide în browser",
                description = "Citește articolul complet la ${article.source}",
                onClick = { runAndClose(onOpenInBrowser) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick)
    )
}
