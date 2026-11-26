package ro.newsfilter.app.ui.screens.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.newsfilter.app.core.audio.NewsAudioPlayer
import ro.newsfilter.app.core.network.toUserMessage
import ro.newsfilter.app.core.util.NetworkMonitor
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.AuthRepository
import ro.newsfilter.app.domain.repository.NewsRepository
import ro.newsfilter.app.worker.NewsSyncScheduler
import javax.inject.Inject

/**
 * Starea UI pentru ecranul principal „Pentru tine".
 */
data class FeedUiState(
    val articles: List<NewsArticle> = emptyList(),
    /** Prima citire din cache-ul local s-a terminat. */
    val cacheLoaded: Boolean = false,
    /** O sincronizare cu n8n este în curs (în fundal). */
    val isSyncing: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    /** Cererea „Forțează actualizarea” către n8n este în curs. */
    val isServerRefreshRunning: Boolean = false,
    val selectedMinutes: Int = 5,
    val selectedCategories: List<String> = emptyList(),
    val totalReadTimeMinutes: Int = 0,
    val error: String? = null,
    /** Prenumele utilizatorului autentificat, pentru salutul personalizat. */
    val userFirstName: String = "",
    /** „Scutură pentru știri noi” activat din Setări. */
    val shakeToRefreshEnabled: Boolean = true
) {
    /** Shimmer doar când nu avem nimic de afișat încă (cache gol și sincronizare în curs). */
    val isLoading: Boolean
        get() = !cacheLoaded || (articles.isEmpty() && isSyncing)
}

/**
 * ViewModel pentru ecranul principal de știri („Pentru tine”) — MVVM cu
 * flux unidirecțional: UI → evenimente → ViewModel → [FeedUiState] → UI.
 *
 * Flux de date (offline-first):
 * 1. Lista vine EXCLUSIV din cache-ul Room ([NewsRepository.observeFeed]) → ecranul se
 *    deschide instantaneu, inclusiv offline.
 * 2. Sincronizarea cu n8n ([loadNews]) rulează în fundal și doar actualizează cache-ul;
 *    lista se actualizează singură. Erorile apar ca ecran de eroare doar dacă nu avem
 *    nimic salvat, altfel ca Snackbar.
 * 3. La revenirea conexiunii se sincronizează automat.
 * 4. „Forțează actualizarea” ([forceServerRefresh]) cere n8n să ruleze ingestia acum și
 *    programează o sincronizare ulterioară (WorkManager), când știrile noi sunt gata.
 * 5. Mesajele (Snackbar) sunt emise o singură dată prin [messages] (Channel).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val newsRepository: NewsRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    private val networkMonitor: NetworkMonitor,
    private val audioPlayer: NewsAudioPlayer,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    /** Expune dacă mini-player-ul audio este activ, pentru a ascunde FAB-ul redundant. */
    val audioState = audioPlayer.state

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Mesaje unice pentru Snackbar (confirmări de acțiuni). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val categories = MutableStateFlow<List<String>?>(null)
    private var syncJob: Job? = null

    init {
        // Starea conexiunii: banner offline + sincronizare automată la revenire.
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                val wasOffline = _uiState.value.isOffline
                _uiState.update { it.copy(isOffline = !isOnline) }
                if (isOnline && wasOffline) loadNews()
            }
        }

        // Datele de profil și setarea senzorului (nu reîncarcă feed-ul).
        viewModelScope.launch {
            preferencesRepository.userPreferences
                .map { prefs -> prefs.userName.trim().substringBefore(' ') to prefs.shakeToRefreshEnabled }
                .distinctUntilChanged()
                .collect { (firstName, shakeEnabled) ->
                    _uiState.update { it.copy(userFirstName = firstName, shakeToRefreshEnabled = shakeEnabled) }
                }
        }

        // Preferințele (categorii + timp): orice modificare din Setări / Onboarding schimbă
        // feed-ul local imediat și pornește o sincronizare.
        viewModelScope.launch {
            preferencesRepository.userPreferences
                .map { prefs -> prefs.selectedCategories.ifEmpty { Category.allSlugs } to prefs.defaultMinutes }
                .distinctUntilChanged()
                .collect { (selected, minutes) ->
                    _uiState.update { it.copy(selectedMinutes = minutes, selectedCategories = selected) }
                    categories.value = selected
                    loadNews()
                }
        }

        // Sursa unică de adevăr pentru listă: cache-ul Room.
        viewModelScope.launch {
            categories.filterNotNull()
                .flatMapLatest { newsRepository.observeFeed(it) }
                .collect { articles ->
                    _uiState.update {
                        it.copy(
                            articles = articles,
                            cacheLoaded = true,
                            totalReadTimeMinutes = articles.sumOf { a -> a.readTimeMinutes },
                            error = if (articles.isNotEmpty()) null else it.error
                        )
                    }
                }
        }

        // Favoritele contului (ex. după reautentificare sau pe un dispozitiv nou).
        viewModelScope.launch {
            try {
                newsRepository.syncBookmarks()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nu blocăm feed-ul; favoritele se sincronizează la următoarea pornire.
            }
        }
    }

    /**
     * Sincronizează cache-ul cu n8n. Lista afișată se actualizează automat din Room.
     * @param isRefresh true pentru pull-to-refresh / buton (indicator vizibil + mesaj la eroare)
     */
    fun loadNews(isRefresh: Boolean = false) {
        val selected = categories.value ?: return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, isRefreshing = isRefresh, error = null) }
            try {
                newsRepository.syncFeed(selected, _uiState.value.selectedMinutes)
                _uiState.update { it.copy(isSyncing = false, isRefreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val state = _uiState.value
                val hasCache = state.articles.isNotEmpty()
                _uiState.update {
                    it.copy(isSyncing = false, isRefreshing = false, error = if (hasCache) null else e.toUserMessage())
                }
                when {
                    !hasCache -> Unit // ecranul de eroare explică deja problema
                    state.isOffline -> if (isRefresh) _messages.send("Ești offline — afișăm știrile salvate.")
                    else -> _messages.send("Nu am putut actualiza știrile: ${e.toUserMessage()}")
                }
            }
        }
    }

    /** Schimbă timpul de lectură și resincronizează. */
    fun onTimeChanged(newMinutes: Int) {
        if (_uiState.value.selectedMinutes == newMinutes) return
        _uiState.update { it.copy(selectedMinutes = newMinutes) }
        loadNews()
    }

    /**
     * „Forțează actualizarea” (app → n8n): pornește acum workflow-ul de ingestie
     * (RSS → scraping → Gemini). Știrile noi sosesc după 1-2 minute printr-o
     * sincronizare programată cu WorkManager (funcționează și dacă ieși din aplicație).
     */
    fun forceServerRefresh() {
        if (_uiState.value.isServerRefreshRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isServerRefreshRunning = true) }
            val message = try {
                if (newsRepository.requestServerRefresh()) {
                    NewsSyncScheduler.scheduleFollowUpSync(context)
                    "Sursele se actualizează. Știrile noi apar automat în 1-2 minute."
                } else {
                    "O actualizare a fost pornită recent. Încearcă din nou peste câteva minute."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Actualizarea nu a putut fi pornită: ${e.toUserMessage()}"
            }
            _uiState.update { it.copy(isServerRefreshRunning = false) }
            _messages.send(message)
        }
    }

    /** Comută bookmark-ul (local imediat, apoi pe server). Lista se actualizează din Room. */
    fun toggleBookmark(articleId: String) {
        val article = _uiState.value.articles.find { it.id == articleId } ?: return
        setBookmark(article, confirm = false)
    }

    /** Varianta din meniul contextual: comută bookmark-ul și confirmă acțiunea. */
    fun toggleBookmarkFromMenu(article: NewsArticle) = setBookmark(article, confirm = true)

    private fun setBookmark(article: NewsArticle, confirm: Boolean) {
        val bookmarked = !article.isBookmarked
        viewModelScope.launch {
            val synced = newsRepository.setBookmarked(article.id, bookmarked)
            when {
                !synced -> _messages.send("Salvat doar pe acest dispozitiv (serverul nu a putut fi contactat).")
                confirm -> _messages.send(
                    if (bookmarked) "Articol salvat pentru mai târziu." else "Articol eliminat din salvate."
                )
            }
        }
    }

    /** Marchează un articol ca citit; dispare automat din feed (interogarea Room exclude citite). */
    fun markAsRead(articleId: String) {
        viewModelScope.launch { newsRepository.markAsRead(articleId) }
    }

    /**
     * Apelat de [ro.newsfilter.app.core.sensor.ShakeDetector] când utilizatorul
     * scutură telefonul: reîmprospătează pachetul (debouncing-ul e în detector).
     */
    fun onShake() {
        if (_uiState.value.isRefreshing || _uiState.value.isSyncing) return
        loadNews(isRefresh = true)
        _messages.trySend("Pachet reîmprospătat prin scuturare!")
    }

    /**
     * Resetează istoricul de citire în Room (toate articolele redevin necitite)
     * și resincronizează. Afișează o confirmare în Snackbar.
     */
    fun resetReadHistory() {
        viewModelScope.launch {
            val count = newsRepository.resetReadHistory()
            _messages.send(
                when (count) {
                    0 -> "Istoricul de citire era deja gol."
                    1 -> "Istoric resetat: 1 articol poate fi citit din nou."
                    else -> "Istoric resetat: $count articole pot fi citite din nou."
                }
            )
            loadNews(isRefresh = true)
        }
    }

    /** Varianta din meniul contextual: marchează articolul ca citit și confirmă acțiunea. */
    fun markAsReadFromMenu(articleId: String) {
        markAsRead(articleId)
        _messages.trySend("Marcat ca citit și scos din pachet.")
    }

    /** Pornește redarea audio (Text-to-Speech) pentru întregul pachet curent de știri. */
    fun playAudioPack() {
        val articles = _uiState.value.articles
        if (articles.isEmpty()) return
        audioPlayer.playPack(articles)
    }

    /**
     * Deconectare: revocă sesiunea pe server și o șterge local. `MainActivity` observă
     * `isLoggedIn = false` și revine automat la `AuthActivity`.
     */
    fun logout() {
        audioPlayer.stop()
        viewModelScope.launch { authRepository.logout() }
    }
}
