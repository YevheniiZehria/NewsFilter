package ro.newsfilter.app.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import ro.newsfilter.app.core.audio.NewsAudioPlayer
import ro.newsfilter.app.core.audio.NewsAudioState
import javax.inject.Inject

/**
 * ViewModel dedicat pentru observarea și controlul mini-player-ului audio din rădăcina UI.
 */
@HiltViewModel
class AudioPlayerViewModel @Inject constructor(
    private val audioPlayer: NewsAudioPlayer
) : ViewModel() {

    val state: StateFlow<NewsAudioState> = audioPlayer.state

    fun togglePlayPause() = audioPlayer.togglePlayPause()

    fun next() = audioPlayer.next()

    fun cycleSpeed() = audioPlayer.cycleSpeed()

    fun cycleVoice() = audioPlayer.cycleVoice()

    fun stop() = audioPlayer.stop()
}
