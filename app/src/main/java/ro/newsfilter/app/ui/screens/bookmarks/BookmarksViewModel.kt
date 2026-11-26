package ro.newsfilter.app.ui.screens.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.NewsRepository
import javax.inject.Inject

data class BookmarksUiState(
    val articles: List<NewsArticle> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val newsRepository: NewsRepository
) : ViewModel() {

    val uiState: StateFlow<BookmarksUiState> = newsRepository.getBookmarkedArticles()
        .map { articles ->
            BookmarksUiState(articles = articles, isLoading = false)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BookmarksUiState(isLoading = true)
        )

    fun removeBookmark(articleId: String) {
        viewModelScope.launch {
            newsRepository.setBookmarked(articleId, bookmarked = false)
        }
    }
}

