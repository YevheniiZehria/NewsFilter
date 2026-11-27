package ro.newsfilter.app.core.notifications

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Abonarea la alertele de piață = topicul FCM `market_alerts` (trimis de jobul de sincronizare).
 * Fără token-uri salvate în Firestore: topicurile sunt gratuite și nu consumă citiri / scrieri.
 */
object MarketAlerts {
    const val TOPIC = "market_alerts"

    /** Aruncă excepție dacă FCM nu e disponibil (ex. offline / fără Google Play Services). */
    suspend fun setSubscribed(subscribed: Boolean) {
        val messaging = FirebaseMessaging.getInstance()
        if (subscribed) messaging.subscribeToTopic(TOPIC).await() else messaging.unsubscribeFromTopic(TOPIC).await()
    }
}
