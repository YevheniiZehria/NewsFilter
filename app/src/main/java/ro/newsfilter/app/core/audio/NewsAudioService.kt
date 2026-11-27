package ro.newsfilter.app.core.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import ro.newsfilter.app.MainActivity
import ro.newsfilter.app.NewsFilterApp
import ro.newsfilter.app.R
import java.util.Locale
import javax.inject.Inject

/**
 * Serviciu Foreground (`mediaPlayback`) care rulează motorul nativ [TextToSpeech]
 * și menține o notificare media cu controale (Anterior, Pauză/Continuă, Următorul, Oprește)
 * astfel încât utilizatorul să poată asculta pachetul de știri și cu ecranul blocat
 * sau din afara aplicației.
 */
@AndroidEntryPoint
class NewsAudioService : Service(), TextToSpeech.OnInitListener {

    @Inject
    lateinit var audioPlayer: NewsAudioPlayer

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeak = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                promoteToForeground()
                if (isTtsReady) {
                    speakCurrentArticle()
                } else {
                    pendingSpeak = true
                }
            }
            ACTION_PAUSE -> {
                pendingSpeak = false
                tts?.stop()
                audioPlayer.onPlaybackStateChanged(isPlaying = false, isPreparing = false)
                promoteToForeground()
            }
            ACTION_NEXT -> {
                audioPlayer.next()
            }
            ACTION_PREVIOUS -> {
                audioPlayer.previous()
            }
            ACTION_SET_SPEED -> {
                tts?.setSpeechRate(audioPlayer.state.value.speechRate)
                if (audioPlayer.state.value.isPlaying && isTtsReady) {
                    speakCurrentArticle()
                }
            }
            ACTION_STOP -> {
                pendingSpeak = false
                tts?.stop()
                audioPlayer.onStoppedFromService()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            audioPlayer.onError("Motorul Text-to-Speech nu este disponibil pe acest dispozitiv.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val engine = tts ?: return
        // Preferăm limba română; dacă nu este instalată pe dispozitiv, folosim limba sistemului / engleză.
        val roLocale = Locale("ro", "RO")
        val roResult = engine.isLanguageAvailable(roLocale)
        when {
            roResult >= TextToSpeech.LANG_AVAILABLE -> engine.language = roLocale
            engine.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE -> {
                engine.language = Locale.getDefault()
            }
            else -> engine.language = Locale.US
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                audioPlayer.onPlaybackStateChanged(isPlaying = true, isPreparing = false)
                promoteToForeground()
            }

            override fun onDone(utteranceId: String?) {
                val hasMore = audioPlayer.onArticleCompleted()
                if (hasMore) {
                    speakCurrentArticle()
                } else {
                    promoteToForeground()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioPlayer.onError("Eroare la redarea audio a articolului.")
                promoteToForeground()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                audioPlayer.onError("Eroare la redarea audio a articolului (cod $errorCode).")
                promoteToForeground()
            }
        })

        isTtsReady = true
        if (pendingSpeak) {
            pendingSpeak = false
            speakCurrentArticle()
        }
    }

    private fun speakCurrentArticle() {
        val state = audioPlayer.state.value
        val article = state.currentArticle ?: return
        val engine = tts ?: return

        engine.setSpeechRate(state.speechRate)
        val textToRead = buildString {
            append(article.displayTitle)
            append(". ")
            if (article.displaySummary.isNotBlank()) {
                append(article.displaySummary)
            }
            // Smart Digest: ideile cheie, dacă există (feed-ul principal și știrile locale îmbogățite).
            if (article.aiBullets.isNotEmpty()) {
                append(" Pe scurt: ")
                article.aiBullets.forEach { append(it.trimEnd('.', ' ')).append(". ") }
            }
        }
        engine.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, article.id)
        promoteToForeground()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    }

    private fun buildNotification(): Notification {
        val state = audioPlayer.state.value
        val article = state.currentArticle
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleText = if (article != null) {
            "Articol ${state.currentIndex + 1}/${state.playlist.size} • ${article.source}"
        } else {
            getString(R.string.app_name)
        }
        val bodyText = article?.displayTitle ?: "Pachet audio pregătit"

        val builder = NotificationCompat.Builder(this, NewsFilterApp.AUDIO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(contentIntent)
            .setOngoing(state.isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.hasPrevious) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                "Anterior",
                servicePendingIntent(ACTION_PREVIOUS, REQUEST_PREV)
            )
        }

        if (state.isPlaying) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pauză",
                servicePendingIntent(ACTION_PAUSE, REQUEST_PLAY_PAUSE)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Redă",
                servicePendingIntent(ACTION_PLAY, REQUEST_PLAY_PAUSE)
            )
        }

        if (state.hasNext) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                "Următorul",
                servicePendingIntent(ACTION_NEXT, REQUEST_NEXT)
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Oprește",
            servicePendingIntent(ACTION_STOP, REQUEST_STOP)
        )

        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, NewsAudioService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        audioPlayer.onStoppedFromService()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 2001

        const val ACTION_PLAY = "ro.newsfilter.app.audio.ACTION_PLAY"
        const val ACTION_PAUSE = "ro.newsfilter.app.audio.ACTION_PAUSE"
        const val ACTION_NEXT = "ro.newsfilter.app.audio.ACTION_NEXT"
        const val ACTION_PREVIOUS = "ro.newsfilter.app.audio.ACTION_PREVIOUS"
        const val ACTION_STOP = "ro.newsfilter.app.audio.ACTION_STOP"
        const val ACTION_SET_SPEED = "ro.newsfilter.app.audio.ACTION_SET_SPEED"

        private const val REQUEST_PREV = 101
        private const val REQUEST_PLAY_PAUSE = 102
        private const val REQUEST_NEXT = 103
        private const val REQUEST_STOP = 104
    }
}
