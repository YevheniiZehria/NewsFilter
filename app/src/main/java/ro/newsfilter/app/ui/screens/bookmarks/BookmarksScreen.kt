package ro.newsfilter.app.ui.screens.bookmarks

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.newsfilter.app.ui.components.CompactNewsCard
import ro.newsfilter.app.ui.components.ScreenHeader

/**
 * Ecranul „Salvate” — articolele marcate cu bookmark, afișate ca și carduri compacte.
 *
 * Starea goală are o mică ilustrație (două „pagini” de ziar suprapuse și un semn
 * de carte), construită direct din componente Compose — fără resurse grafice.
 *
 * Insets: `statusBarsPadding()` o singură dată pe rădăcină; partea de jos e
 * tratată de Scaffold-ul rădăcină (BottomNavBar). Fără Scaffold imbricat.
 */
@Composable
fun BookmarksScreen(
    onArticleClick: (String) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val contentState = when {
        uiState.isLoading -> BookmarksContent.Loading
        uiState.articles.isEmpty() -> BookmarksContent.Empty
        else -> BookmarksContent.List
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        ScreenHeader(
            title = "Salvate",
            subtitle = when (val n = uiState.articles.size) {
                0 -> "Articolele tale de citit mai târziu"
                1 -> "1 articol salvat"
                in 2..19 -> "$n articole salvate"
                else -> "$n de articole salvate"
            }
        )

        Crossfade(
            targetState = contentState,
            label = "bookmarks_content",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            when (state) {
                BookmarksContent.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                BookmarksContent.Empty -> EmptyBookmarks()

                BookmarksContent.List -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = uiState.articles, key = { it.id }) { article ->
                        CompactNewsCard(
                            article = article,
                            onArticleClick = onArticleClick,
                            onBookmarkClick = viewModel::removeBookmark,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

private enum class BookmarksContent { Loading, Empty, List }

/** Starea goală ilustrată: „pagini” suprapuse + semn de carte + mesaj prietenos. */
@Composable
private fun EmptyBookmarks() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BookmarkIllustration()

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Nicio știre salvată încă",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Când găsești un articol interesant, atinge semnul de carte și îl vei regăsi aici, " +
                "gata de citit oricând.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BookmarkIllustration() {
    val page = MaterialTheme.colorScheme.surfaceContainerLow
    val line = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        // Cerc de fundal
        Box(
            modifier = Modifier
                .size(170.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        )
        // Pagina din spate (înclinată)
        IllustrationPage(
            color = page,
            lineColor = line,
            modifier = Modifier
                .offset(x = (-18).dp, y = 6.dp)
                .rotate(-10f)
        )
        // Pagina din față
        IllustrationPage(
            color = page,
            lineColor = line,
            modifier = Modifier
                .offset(x = 14.dp, y = (-4).dp)
                .rotate(6f)
        )
        // Semnul de carte
        Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .offset(x = 40.dp, y = (-42).dp)
                .size(36.dp)
        )
    }
}

@Composable
private fun IllustrationPage(
    color: androidx.compose.ui.graphics.Color,
    lineColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(86.dp)
            .height(108.dp)
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(lineColor)
        )
        listOf(1f, 0.85f, 0.6f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(lineColor)
            )
        }
    }
}
