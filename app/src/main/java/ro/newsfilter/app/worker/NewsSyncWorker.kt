package ro.newsfilter.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import ro.newsfilter.app.data.preferences.UserPreferencesRepository
import ro.newsfilter.app.domain.model.Category
import ro.newsfilter.app.domain.repository.NewsRepository
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sincronizare în fundal (offline-first): aduce știrile noi din Cloud Firestore în cache-ul Room,
 * ca aplicația să se deschidă direct cu conținut proaspăt, chiar și fără rețea.
 *
 * Rulează periodic (la ~3 ore, doar cu rețea) și o dată după „Forțează actualizarea”,
 * când workflow-ul de ingestie a avut timp să termine.
 */
@HiltWorker
class NewsSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val newsRepository: NewsRepository,
    private val preferencesRepository: UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = preferencesRepository.userPreferences.first()
        if (!prefs.isLoggedIn) return Result.success()

        return try {
            newsRepository.syncFeed(
                categories = Category.effective(prefs.selectedCategories),
                maxMinutes = prefs.defaultMinutes
            )
            newsRepository.clearOldCache(daysOld = 7)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Sincronizare amânată (rețea): ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sincronizarea în fundal a eșuat", e)
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "NewsSyncWorker"
    }
}

/** Programarea sincronizărilor (WorkManager). */
object NewsSyncScheduler {

    private const val PERIODIC_WORK = "news_sync_periodic"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Sincronizare periodică; KEEP = nu se reprogramează la fiecare pornire a aplicației. */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<NewsSyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
