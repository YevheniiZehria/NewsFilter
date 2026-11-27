package ro.newsfilter.app.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import ro.newsfilter.app.MainActivity
import ro.newsfilter.app.NewsFilterApp
import ro.newsfilter.app.R
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.Category
import kotlinx.coroutines.CancellationException
import ro.newsfilter.app.domain.repository.NewsRepository

/**
 * Worker programat zilnic pentru a compune și afișa notificarea cu pachetul de știri.
 *
 * Decizie tehnică:
 * - Folosește @HiltWorker pentru injectarea asincronă a dependențelor.
 * - Citește preferințele utilizatorului (categorii + minute) din DataStore.
 * - Solicită de la repository pachetul de știri personalizat.
 * - Emite o notificare stil BigTextStyle cu titlurile articolelor selectate.
 */
@HiltWorker
class DailyNewsNotificationWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val newsRepository: NewsRepository,
    private val preferencesRepository: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = preferencesRepository.userPreferences.first()
            // Fără notificări pentru utilizatorii deconectați
            if (!prefs.dailyNotificationEnabled || !prefs.isLoggedIn) {
                return Result.success()
            }

            val categories = Category.effective(prefs.selectedCategories)

            // Încercăm întâi o sincronizare; dacă rețeaua lipsește, notificarea folosește
            // știrile deja salvate local (offline-first).
            try {
                newsRepository.syncFeed(categories, prefs.defaultMinutes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            val articles = newsRepository.observeFeed(categories).first()

            if (articles.isNotEmpty()) {
                showNotification(articles.size, prefs.defaultMinutes, articles.map { it.displayTitle })
            }

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(count: Int, minutes: Int, titles: List<String>) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val summaryText = titles.take(3).joinToString("\n• ", prefix = "• ")

        val notification = NotificationCompat.Builder(appContext, NewsFilterApp.NOTIFICATION_CHANNEL_ID)
            // Iconiță vectorială monocromă proprie (res/drawable/ic_notification.xml);
            // setColor colorează iconița și numele aplicației în sertarul de notificări.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(appContext, R.color.notification_accent))
            .setContentTitle("Pachetul tău de știri ($minutes min)")
            .setContentText("Am pregătit $count știri relevante pentru tine astăzi.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Ai $count știri selectate pentru timpul tău ($minutes min):\n$summaryText")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "daily_news_notification_work"
    }
}

