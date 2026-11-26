package ro.newsfilter.app.core.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ro.newsfilter.app.domain.model.NewsArticle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller singleton pentru redarea audio a pachetului de știri prin Text-to-Speech.
 *
 * Face legătura dintre UI (Feed / Mini-Player) și [NewsAudioService] (Foreground Service),
 * expunând un [StateFlow] reactiv cu starea curentă a redării.
 */
@Singleton
class NewsAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(NewsAudioState())
    val state: StateFlow<NewsAudioState> = _state.asStateFlow()

    /** Pornește redarea unui pachet de articole de la început (sau de la un index dat). */
    fun playPack(articles: List<NewsArticle>, startIndex: Int = 0) {
        if (articles.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, articles.lastIndex)
        _state.update {
            it.copy(
                isVisible = true,
                isPlaying = false,
                isPreparing = true,
                playlist = articles,
                currentIndex = safeIndex,
                error = null
            )
        }
        sendServiceAction(NewsAudioService.ACTION_PLAY, isForegroundStart = true)
    }

    /** Comută între redare și pauză. */
    fun togglePlayPause() {
        val current = _state.value
        if (!current.isVisible || current.playlist.isEmpty()) return
        val action = if (current.isPlaying) {
            NewsAudioService.ACTION_PAUSE
        } else {
            NewsAudioService.ACTION_PLAY
        }
        sendServiceAction(action, isForegroundStart = !current.isPlaying)
    }

    /** Trece la următorul articol din pachet. */
    fun next() {
        val current = _state.value
        if (!current.hasNext) return
        _state.update { it.copy(currentIndex = it.currentIndex + 1, isPreparing = true) }
        sendServiceAction(NewsAudioService.ACTION_PLAY, isForegroundStart = true)
    }

    /** Revine la articolul anterior din pachet. */
    fun previous() {
        val current = _state.value
        if (!current.hasPrevious) return
        _state.update { it.copy(currentIndex = it.currentIndex - 1, isPreparing = true) }
        sendServiceAction(NewsAudioService.ACTION_PLAY, isForegroundStart = true)
    }

    /** Schimbă ciclic viteza de citire: 1.0x → 1.25x → 1.5x → 0.85x → 1.0x. */
    fun cycleSpeed() {
        val nextSpeed = when (_state.value.speechRate) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 0.85f
            else -> 1.0f
        }
        _state.update { it.copy(speechRate = nextSpeed) }
        if (_state.value.isVisible) {
            sendServiceAction(NewsAudioService.ACTION_SET_SPEED, isForegroundStart = false)
        }
    }

    /** Oprește redarea, închide serviciul foreground și ascunde mini-player-ul. */
    fun stop() {
        _state.update {
            NewsAudioState(speechRate = it.speechRate)
        }
        sendServiceAction(NewsAudioService.ACTION_STOP, isForegroundStart = false)
    }

    /** Actualizează starea internă din [NewsAudioService]. */
    internal fun onPlaybackStateChanged(isPlaying: Boolean, isPreparing: Boolean = false) {
        _state.update {
            it.copy(isPlaying = isPlaying, isPreparing = isPreparing, error = null)
        }
    }

    /** Apelat când un articol s-a terminat de citit; avansează automat la următorul. */
    internal fun onArticleCompleted(): Boolean {
        val current = _state.value
        return if (current.hasNext) {
            _state.update { it.copy(currentIndex = it.currentIndex + 1, isPreparing = false) }
            true
        } else {
            _state.update { it.copy(isPlaying = false, isPreparing = false) }
            false
        }
    }

    /** Raportează o eroare a motorului TTS. */
    internal fun onError(message: String) {
        _state.update {
            it.copy(isPlaying = false, isPreparing = false, error = message)
        }
    }

    /** Resetare completă a stării când serviciul este oprit din notificare. */
    internal fun onStoppedFromService() {
        _state.update { NewsAudioState(speechRate = it.speechRate) }
    }

    private fun sendServiceAction(action: String, isForegroundStart: Boolean) {
        val intent = Intent(context, NewsAudioService::class.java).apply {
            this.action = action
        }
        if (isForegroundStart) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
