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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.newsfilter.app.core.audio.NewsAudioPlayer
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
 * - Acțiuni în Firestore: vot (`users/{uid}/votes` + contoarele `articles/{id}`), favorit (`users/{uid}/bookmarks`), „Aprofundează” (`articles/{id}`).
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val newsRepository: NewsRepository,
    private val audioPlayer: NewsAudioPlayer
) : ViewModel() {

    private val articleId: String = checkNotNull(savedStateHandle["articleId"])

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Mesaje unice pentru Snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** True cât timp acest articol se redă (sau se pregătește) în player-ul Text-to-Speech. */
    val isAudioPlaying: StateFlow<Boolean> = audioPlayer.state
        .map { it.isVisible && (it.isPlaying || it.isPreparing) && it.currentArticle?.id == articleId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        loadArticle()
    }

    /** Briefing audio (TTS în română) doar pentru acest articol; a doua apăsare oprește redarea. */
    fun toggleAudio() {
        if (isAudioPlaying.value) {
            audioPlayer.stop()
        } else {
            _uiState.value.article?.let { audioPlayer.playPack(listOf(it)) }
        }
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
                        // Votul se poate retrage / schimba: Room (actualizat optimist de setVote) decide.
                        feedbackPositive = article.userVote,
                        isFeedbackSent = article.userVote != null,
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
     * Votul comunității: apăsarea aceluiași buton retrage votul, celălalt buton îl schimbă.
     * Actualizare optimistă (UI + Room); dacă Firestore respinge / e offline, revenim la votul anterior.
     */
    fun vote(isPositive: Boolean) {
        val article = _uiState.value.article ?: return
        val before = article.userVote
        val after = if (before == isPositive) null else isPositive
        showVote(article, before, after)
        viewModelScope.launch {
            if (!newsRepository.setVote(articleId, after)) {
                _uiState.value.article?.let { showVote(it, after, before) }
                _messages.send("Votul nu a putut fi trimis. Verifică conexiunea și încearcă din nou.")
            }
        }
    }

    private fun showVote(article: NewsArticle, before: Boolean?, after: Boolean?) {
        fun delta(positive: Boolean) = (if (after == positive) 1 else 0) - (if (before == positive) 1 else 0)
        _uiState.update {
            it.copy(
                isFeedbackSent = after != null,
                feedbackPositive = after,
                article = article.copy(
                    userVote = after,
                    upvotes = (article.upvotes + delta(true)).coerceAtLeast(0),
                    downvotes = (article.downvotes + delta(false)).coerceAtLeast(0)
                )
            )
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
