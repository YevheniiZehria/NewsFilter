package ro.newsfilter.app.core.notifications

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.newsfilter.app.MainActivity

/** Destinația unei notificări push (alerte de piață). */
sealed interface DeepLink {
    /** Știre cu impact mare → ecranul de detaliu (rezumatul în română). */
    data class Article(val articleId: String) : DeepLink

    /** Mișcare bruscă de curs → Feed, cu widget-ul valutar pe perechea respectivă. */
    data class MarketPair(val pair: String) : DeepLink
}

/**
 * Legătura dintre notificări și navigarea Compose: `MainActivity` pune aici destinația
 * din Intent (onCreate / onNewIntent), iar `NewsFilterAppContent` / `FeedScreen` o
 * consumă o singură dată ([consume]).
 */
object DeepLinks {
    private const val EXTRA_ARTICLE_ID = "ro.newsfilter.app.extra.ARTICLE_ID"
    private const val EXTRA_PAIR = "ro.newsfilter.app.extra.PAIR"

    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    /** Intent spre `MainActivity` pentru o notificare (reutilizează Activity-ul dacă e deschis). */
    fun intentFor(context: Context, link: DeepLink): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            when (link) {
                is DeepLink.Article -> putExtra(EXTRA_ARTICLE_ID, link.articleId)
                is DeepLink.MarketPair -> putExtra(EXTRA_PAIR, link.pair)
            }
        }

    /** Citește destinația din Intent și o elimină din extras (nu se repetă la recreare). */
    fun handle(intent: Intent?) {
        intent ?: return
        val link = intent.getStringExtra(EXTRA_ARTICLE_ID)?.let { DeepLink.Article(it) }
            ?: intent.getStringExtra(EXTRA_PAIR)?.let { DeepLink.MarketPair(it) }
            ?: return
        intent.removeExtra(EXTRA_ARTICLE_ID)
        intent.removeExtra(EXTRA_PAIR)
        _pending.value = link
    }

    fun consume(link: DeepLink) {
        _pending.compareAndSet(link, null)
    }
}
