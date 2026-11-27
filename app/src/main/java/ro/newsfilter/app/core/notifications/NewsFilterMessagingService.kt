package ro.newsfilter.app.core.notifications

import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ro.newsfilter.app.NewsFilterApp
import ro.newsfilter.app.R
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import javax.inject.Inject

/**
 * Primește alertele de piață (topicul `market_alerts`, mesaje „data-only” trimise de jobul de
 * sincronizare) și afișează notificarea:
 *  - `type = rate` (mișcare bruscă de curs) → atingerea deschide Feed-ul cu widget-ul pe perechea `pair`;
 *  - `type = news` (știre cu impact mare) → atingerea deschide detaliul articolului `articleId`.
 * Notificarea e ignorată dacă utilizatorul e deconectat sau a dezactivat alertele.
 */
@AndroidEntryPoint
class NewsFilterMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: return
        val body = data["body"] ?: message.notification?.body.orEmpty()

        // Rulează pe un fir de fundal al FCM: citirea sincronă din DataStore e permisă aici.
        val prefs = runBlocking { preferencesRepository.userPreferences.first() }
        if (!prefs.isLoggedIn || !prefs.marketAlertsEnabled) return

        val link = when (data["type"]) {
            "news" -> data["articleId"]?.let { DeepLink.Article(it) }
            else -> data["pair"]?.let { DeepLink.MarketPair(it) }
        }
        val intent = link?.let { DeepLinks.intentFor(this, it) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: return
        val requestCode = (link?.toString() ?: title).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NewsFilterApp.MARKET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(requestCode, notification)
            } catch (_: SecurityException) {
                // Permisiunea POST_NOTIFICATIONS a fost revocată între timp.
            }
        }
    }

    /** Alertele folosesc doar topicuri: token-ul nu trebuie trimis nicăieri. */
    override fun onNewToken(token: String) = Unit
}
