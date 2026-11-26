package ro.newsfilter.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.newsfilter.app.core.network.toUserMessage
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.NewsRepository
import javax.inject.Inject

/**
 * Starea UI pentru ecranul de detaliu al unui articol.
 */
data class DetailUiState(
    val article: NewsArticle? = null,
    val isLoading: Boolean = true,
    val isFeedbackSent: Boolean = false,
    val feedbackPositive: Boolean? = null,
    val error: String? = null,
    /** Analiza „Aprofundează subiectul” (din cache sau citită acum din Firestore). */
    val deepDive: String? = null,
    val isDeepDiveLoading: Boolean = false,
    val deepDiveError: String? = null
)

/**
 * ViewModel pentru ecranul de detaliu.
 *
 * - Preia articleId din SavedStateHandle (furnizat automat de Navigation Compose).
 * - Încarcă articolul din cache-ul local (Room) — funcționează și offline.
 * - La deschidere, marchează automat articolul ca citit.
 * - Restaurează votul salvat (Like / Dislike rămâne vizibil la redeschidere).
 * - Acțiuni în Firestore: vot (`users/{uid}/votes`), favorit (`users/{uid}/bookmarks`), „Aprofundează” (`articles/{id}`).
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val newsRepository: NewsRepository
) : ViewModel() {

    private val articleId: String = checkNotNull(savedStateHandle["articleId"])

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Mesaje unice pentru Snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        loadArticle()
    }

    /**
     * Observă articolul din Room: dacă rezumatul AI sau aprofundarea sosesc (sincronizare în
     * fundal) cât timp ecranul e deschis, apar imediat. Prima emisie marchează articolul citit.
     */
    private fun loadArticle() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            var first = true
            newsRepository.observeArticle(articleId).collect { article ->
                if (article == null) {
                    // Păstrăm articolul deja afișat (ex. cache golit între timp).
                    if (_uiState.value.article == null) {
                        _uiState.update { it.copy(isLoading = false, error = "Articolul nu a fost găsit.") }
                    }
                    return@collect
                }
                _uiState.update {
                    it.copy(
                        article = article,
                        isLoading = false,
                        error = null,
                        feedbackPositive = it.feedbackPositive ?: article.userVote,
                        isFeedbackSent = it.isFeedbackSent || article.userVote != null,
                        deepDive = it.deepDive ?: article.deepDive
                    )
                }
                if (first) {
                    first = false
                    newsRepository.markAsRead(articleId)
                }
            }
        }
    }

    /** Comută starea de salvare: local imediat, apoi pe server. */
    fun toggleBookmark() {
        val current = _uiState.value.article ?: return
        val bookmarked = !current.isBookmarked
        _uiState.update { it.copy(article = current.copy(isBookmarked = bookmarked)) }
        viewModelScope.launch {
            if (!newsRepository.setBookmarked(articleId, bookmarked)) {
                _messages.send("Salvat doar pe acest dispozitiv (serverul nu a putut fi contactat).")
            }
        }
    }

    /**
     * Salvează feedback-ul (like/dislike) în Firestore (o singură dată per articol)
     * și salvează votul local, ca să fie reținut la redeschiderea articolului.
     */
    fun sendFeedback(isPositive: Boolean) {
        if (_uiState.value.isFeedbackSent) return
        // Actualizare optimistă: UI-ul reacționează instantaneu, votul se salvează
        // în Room, iar votul se salvează în Firestore în fundal.
        _uiState.update { state ->
            state.copy(
                isFeedbackSent = true,
                feedbackPositive = isPositive,
                article = state.article?.copy(userVote = isPositive)
            )
        }
        viewModelScope.launch {
            newsRepository.setUserVote(articleId, isPositive)
            newsRepository.sendFeedback(articleId, isPositive)
        }
    }

    /**
     * „Aprofundează subiectul” (pre-generat de Gemini, citit din Firestore): context, de ce contează, ce urmează.
     * Rezultatul este păstrat în Room, deci a doua oară apare instantaneu și offline.
     */
    fun requestDeepDive() {
        val state = _uiState.value
        if (state.isDeepDiveLoading || state.deepDive != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeepDiveLoading = true, deepDiveError = null) }
            try {
                val text = newsRepository.requestDeepDive(articleId)
                _uiState.update { it.copy(isDeepDiveLoading = false, deepDive = text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isDeepDiveLoading = false, deepDiveError = e.toUserMessage()) }
            }
        }
    }
}
