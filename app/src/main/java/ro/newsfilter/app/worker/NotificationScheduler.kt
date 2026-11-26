package ro.newsfilter.app.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Programează notificarea zilnică „Pachetul tău de știri” cu **WorkManager**.
 *
 * Flux: Setări (switch + TimePickerDialog) → `SettingsViewModel` → [scheduleDailyNotification]
 * → `PeriodicWorkRequest` (24h) cu întârziere inițială până la ora aleasă →
 * [DailyNewsNotificationWorker] cere pachetul de la `NewsRepository` și afișează notificarea.
 *
 * Decizie tehnică: lucrare unică (`enqueueUniquePeriodicWork` + `UPDATE`), deci
 * reprogramarea înlocuiește lucrarea existentă în loc să creeze duplicate.
 * WorkManager supraviețuiește repornirii telefonului și respectă Doze.
 */
object NotificationScheduler {

    /** Programează (sau reprogramează) notificarea zilnică la [hour]:[minute]. */
    fun scheduleDailyNotification(context: Context, hour: Int = 8, minute: Int = 0) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val initialDelay = target.timeInMillis - now.timeInMillis

        val workRequest = PeriodicWorkRequestBuilder<DailyNewsNotificationWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyNewsNotificationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    /** Anulează notificarea zilnică. */
    fun cancelDailyNotification(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailyNewsNotificationWorker.WORK_NAME)
    }
}

