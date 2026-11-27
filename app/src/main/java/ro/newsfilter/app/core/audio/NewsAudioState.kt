package ro.newsfilter.app.core.audio

import ro.newsfilter.app.domain.model.NewsArticle

/**
 * Starea curentă a player-ului audio Text-to-Speech (TTS).
 *
 * @property isVisible Dacă mini-player-ul este afișat în interfață
 * @property isPlaying Dacă motorul TTS redă în prezent un articol
 * @property isPreparing Dacă motorul TTS se inițializează
 * @property playlist Lista de articole din pachetul curent
 * @property currentIndex Indexul articolului redat în prezent
 * @property speechRate Viteza de redare (0.85x, 1.0x, 1.25x, 1.5x)
 * @property voice Presetul de voce (Standard / Narator)
 * @property error Mesaj de eroare, dacă TTS nu este disponibil pe dispozitiv
 */
data class NewsAudioState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val playlist: List<NewsArticle> = emptyList(),
    val currentIndex: Int = 0,
    val speechRate: Float = 1.0f,
    val voice: NarratorVoice = NarratorVoice.STANDARD,
    val error: String? = null
) {
    val currentArticle: NewsArticle?
        get() = playlist.getOrNull(currentIndex)

    val hasNext: Boolean
        get() = currentIndex < playlist.lastIndex

    val hasPrevious: Boolean
        get() = currentIndex > 0

    val progressFraction: Float
        get() = if (playlist.isEmpty()) 0f else (currentIndex + 1f) / playlist.size
}
