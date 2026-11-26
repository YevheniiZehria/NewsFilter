package ro.newsfilter.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ro.newsfilter.app.core.auth.SessionManager
import ro.newsfilter.app.worker.NewsSyncScheduler
import javax.inject.Inject

/**
 * Application class cu @HiltAndroidApp — punct de intrare pentru Hilt DI.
 *
 * Decizie tehnică: Implementăm Configuration.Provider pentru a injecta
 * HiltWorkerFactory în WorkManager. Asta permite ca WorkManager-ul să
 * creeze Worker-i cu dependențe Hilt (@HiltWorker).
 *
 * Creăm și canalul de notificări la pornirea aplicației (necesar pe API 26+).
 */
@HiltAndroidApp
class NewsFilterApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionManager: SessionManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Oglindește starea Firebase Auth în DataStore (deconectare automată dacă sesiunea dispare).
        sessionManager.start()
        // Sincronizare periodică în fundal (offline-first); workerul nu face nimic fără sesiune.
        NewsSyncScheduler.schedulePeriodicSync(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val dailyChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_name_news),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_desc_news)
            }
            notificationManager.createNotificationChannel(dailyChannel)

            val audioChannel = NotificationChannel(
                AUDIO_CHANNEL_ID,
                getString(R.string.channel_name_audio),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc_audio)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(audioChannel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "daily_news_channel"
        const val AUDIO_CHANNEL_ID = "audio_playback_channel"
    }
}

