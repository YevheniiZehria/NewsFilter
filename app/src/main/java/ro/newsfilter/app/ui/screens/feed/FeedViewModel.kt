package ro.newsfilter.app.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import ro.newsfilter.app.domain.model.Currency
import ro.newsfilter.app.domain.model.MarketEvent
import ro.newsfilter.app.domain.model.MarketHistory
import ro.newsfilter.app.domain.model.MarketHistoryEvent
import ro.newsfilter.app.domain.model.MarketPairs
import ro.newsfilter.app.domain.model.MarketPulse
import ro.newsfilter.app.domain.model.MarketRange
import ro.newsfilter.app.domain.model.NewsArticle
import ro.newsfilter.app.domain.repository.AuthRepository
import ro.newsfilter.app.domain.repository.MarketRepository
import ro.newsfilter.app.domain.repository.NewsRepository
import javax.inject.Inject

/**
 * Starea UI pentru ecranul principal „Pentru tine".
 */
data class FeedUiState(
    val articles: List<NewsArticle> = emptyList(),
    /** Prima citire din cache-ul local s-a terminat. */
    val cacheLoaded: Boolean = false,
    /** O sincronizare cu Firestore este în curs (în fundal). */
    val isSyncing: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    /** „Forțează actualizarea” (resincronizare din Firestore) este în curs. */
    val isServerRefreshRunning: Boolean = false,
    val selectedMinutes: Int = 5,
    val selectedCategories: List<String> = emptyList(),
    val totalReadTimeMinutes: Int = 0,
    val error: String? = null,
    /** Prenumele utilizatorului autentificat, pentru salutul personalizat. */
    val userFirstName: String = "",
    /** „Scutură pentru știri noi” activat din Setări. */
    val shakeToRefreshEnabled: Boolean = true,
    /** Market Pulse: ultimele cursuri din `market/pulse` (doar în memorie). */
    val marketPulse: MarketPulse? = null,
    /** Ultima actualizare Market Pulse a eșuat (offline / eroare) → afișăm „neactualizat”. */
    val marketPulseStale: Boolean = false,
    /** Widget-ul valutar din capul Feed-ului (pereche, direcție, interval, istoric). */
    val marketWidget: MarketWidgetState = MarketWidgetState()
) {
    /** Shimmer doar când nu avem nimic de afișat încă (cache gol și sincronizare în curs). */
    val isLoading: Boolean
        get() = !cacheLoaded || (articles.isEmpty() && isSyncing)
}

/**
 * Starea widget-ului valutar (convertor + grafic).
 * @property pair perechea stocată (ex. „USD/RON”)
 * @property inverted direcția inversă (ex. RON → USD): cursul afișat este 1/x
 * @property history seria brută a perechii (din memorie sau proaspăt descărcată); null = încă nimic
 * @property isStale actualizarea a eșuat, se afișează ultimele date descărcate
 * @property error mesajul afișat când nu avem nimic de arătat (ex. offline la prima deschidere)
 */
data class MarketWidgetState(
    val pair: String = MarketPairs.DEFAULT,
    val inverted: Boolean = false,
    val range: MarketRange = MarketRange.M1,
    val history: MarketHistory? = null,
    val isLoading: Boolean = false,
    val isStale: Boolean = false,
    val error: String? = null
) {
    /** Moneda de bază / cotată în direcția afișată (ex. USD → RON). */
    val from: Currency get() = MarketPairs.split(pair).let { if (inverted) it.second else it.first }
    val to: Currency get() = MarketPairs.split(pair).let { if (inverted) it.first else it.second }

    /** Seria în direcția afișată. */
    val displayHistory: MarketHistory? get() = if (inverted) history?.inverted() else history
}

/** Unde se deschide știrea din bannerul Market Pulse. */
sealed interface MarketEventTarget {
    /** Articolul există în cache-ul Room → ecranul de detaliu. */
    data class Detail(val articleId: String) : MarketEventTarget

    /** Articolul nu e în cache (altă categorie) → browser. */
    data class Browser(val url: String) : MarketEventTarget
}

/**
 * ViewModel pentru ecranul principal de știri („Pentru tine”) — MVVM cu
 * flux unidirecțional: UI → evenimente → ViewModel → [FeedUiState] → UI.
 *
 * Flux de date (offline-first):
 * 1. Lista vine EXCLUSIV din cache-ul Room ([NewsRepository.observeFeed]) → ecranul se
 *    deschide instantaneu, inclusiv offline.
 * 2. Sincronizarea cu Cloud Firestore ([loadNews]) rulează în fundal și doar actualizează cache-ul;
 *    lista se actualizează singură. Erorile apar ca ecran de eroare doar dacă nu avem
 *    nimic salvat, altfel ca Snackbar.
 * 3. La revenirea conexiunii se sincronizează automat.
 * 4. „Forțează actualizarea” ([forceServerRefresh]) resincronizează imediat din Firestore
 *    (sursele sunt procesate automat la 30 de minute de jobul de sincronizare).
 * 5. Mesajele (Snackbar) sunt emise o singură dată prin [messages] (Channel).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val newsRepository: NewsRepository,
    private val marketRepository: MarketRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository,
    private val networkMonitor: NetworkMonitor,
    private val audioPlayer: NewsAudioPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    /** Expune dacă mini-player-ul audio este activ, pentru a ascunde FAB-ul redundant. */
    val audioState = audioPlayer.state

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Mesaje unice pentru Snackbar (confirmări de acțiuni). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _marketEventTargets = Channel<MarketEventTarget>(Channel.BUFFERED)

    /** Navigarea cerută de bannerul Market Pulse (emisă o singură dată). */
    val marketEventTargets: Flow<MarketEventTarget> = _marketEventTargets.receiveAsFlow()

    private val categories = MutableStateFlow<List<String>?>(null)
    private var syncJob: Job? = null
    private var chartJob: Job? = null

    init {
        // Starea conexiunii: banner offline + sincronizare automată la revenire.
        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                val wasOffline = _uiState.value.isOffline
                _uiState.update { it.copy(isOffline = !isOnline) }
                if (isOnline && wasOffline) {
                    loadNews()
                    refreshMarketPulse()
                }
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
     * Sincronizează cache-ul cu Firestore. Lista afișată se actualizează automat din Room.
     * @param isRefresh true pentru pull-to-refresh / buton (indicator vizibil + mesaj la eroare)
     */
    fun loadNews(isRefresh: Boolean = false) {
        if (isRefresh) refreshMarketPulse()
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

    /**
     * Market Pulse în timp real: ascultă cursurile din Firestore cât timp este apelată
     * (FeedScreen o rulează doar cât ecranul este vizibil — STARTED — și o anulează apoi).
     * Fiecare actualizare a cursurilor reîmprospătează și graficul. Erorile nu se afișează:
     * păstrăm ultimele valori și le marcăm „neactualizat”.
     */
    suspend fun collectMarketPulse() {
        try {
            marketRepository.observeMarketPulse().collect { pulse ->
                val offline = _uiState.value.isOffline
                _uiState.update { it.copy(marketPulse = pulse, marketPulseStale = offline) }
                if (!_uiState.value.marketWidget.isLoading) loadMarketHistory()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update { it.copy(marketPulseStale = true) }
        }
    }

    /**
     * Reîmprospătare manuală / scuturare / revenirea conexiunii: cursurile vin deja în timp real,
     * deci reîncărcăm doar graficul (repository-ul servește din memorie datele recente).
     */
    fun refreshMarketPulse() {
        if (_uiState.value.isOffline) {
            _uiState.update { it.copy(marketPulseStale = it.marketPulse != null) }
            return
        }
        if (!_uiState.value.marketWidget.isLoading) loadMarketHistory()
    }

    /** Banner Market Pulse: detaliul articolului dacă e în cache, altfel link-ul original. */
    fun openMarketEvent(event: MarketEvent) = openMarketArticle(event.articleId, event.articleUrl)

    /** Punct-știre de pe grafic: același comportament ca bannerul. */
    fun openChartEvent(event: MarketHistoryEvent) = openMarketArticle(event.articleId, event.articleUrl)

    private fun openMarketArticle(articleId: String, articleUrl: String) {
        viewModelScope.launch {
            val target = when {
                newsRepository.getArticleById(articleId) != null -> MarketEventTarget.Detail(articleId)
                articleUrl.isNotBlank() -> MarketEventTarget.Browser(articleUrl)
                else -> return@launch
            }
            _marketEventTargets.send(target)
        }
    }

    /**
     * Schimbă perechea / direcția widget-ului (chip-uri sau listele derulante ale convertorului).
     * Inversarea direcției nu cere date noi: seria se afișează ca 1/x.
     */
    fun selectMarketPair(pair: String, inverted: Boolean = false) {
        val widget = _uiState.value.marketWidget
        if (widget.pair == pair && widget.inverted == inverted) return
        _uiState.update { it.copy(marketWidget = widget.copy(pair = pair, inverted = inverted)) }
        if (widget.pair != pair) loadMarketHistory()
    }

    /** Butonul ⇅ al convertorului: inversează direcția (ex. USD → RON devine RON → USD). */
    fun swapMarketDirection() {
        _uiState.update { it.copy(marketWidget = it.marketWidget.copy(inverted = !it.marketWidget.inverted)) }
    }

    /** Selectorul de interval 1 Z / 5 Z / 1 L / 1 A / Max. */
    fun selectMarketRange(range: MarketRange) {
        val widget = _uiState.value.marketWidget
        if (widget.range == range) return
        _uiState.update { it.copy(marketWidget = widget.copy(range = range)) }
        loadMarketHistory()
    }

    /**
     * Încarcă istoricul pentru perechea / intervalul curent: afișează imediat ce avem în memorie,
     * apoi actualizează de pe server. Offline / la eroare păstrează ultimele date („date salvate”).
     */
    private fun loadMarketHistory() {
        val widget = _uiState.value.marketWidget
        val (pair, range) = widget.pair to widget.range
        val cached = marketRepository.cachedMarketHistory(pair, range)
        chartJob?.cancel()

        fun updateIfCurrent(transform: (MarketWidgetState) -> MarketWidgetState) = _uiState.update { state ->
            val w = state.marketWidget
            if (w.pair == pair && w.range == range) state.copy(marketWidget = transform(w)) else state
        }

        if (_uiState.value.isOffline) {
            updateIfCurrent {
                it.copy(
                    history = cached,
                    isLoading = false,
                    isStale = cached != null,
                    error = if (cached == null) "Graficul nu este disponibil offline." else null
                )
            }
            return
        }
        updateIfCurrent { it.copy(history = cached, isLoading = true, error = null) }
        chartJob = viewModelScope.launch {
            try {
                val history = marketRepository.fetchMarketHistory(pair, range)
                updateIfCurrent { it.copy(history = history, isLoading = false, isStale = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateIfCurrent {
                    it.copy(
                        isLoading = false,
                        isStale = it.history != null,
                        error = if (it.history == null) "Graficul nu a putut fi încărcat: ${e.toUserMessage()}" else null
                    )
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
     * „Forțează actualizarea”: resincronizează acum știrile din Firestore. Procesarea surselor
     * (RSS → scraping → Gemini) rulează automat la 30 de minute în jobul de sincronizare
     * (GitHub Actions), deci aplicația nu o mai pornește direct.
     */
    fun forceServerRefresh() {
        if (_uiState.value.isServerRefreshRunning) return
        val selected = categories.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isServerRefreshRunning = true) }
            val message = try {
                newsRepository.syncFeed(selected, _uiState.value.selectedMinutes)
                "Știrile sunt la zi. Sursele noi sunt procesate automat la fiecare 30 de minute."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Actualizarea a eșuat: ${e.toUserMessage()}"
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
