package app.rev.data.offline

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** One unique WorkManager job per track, so a track is never downloaded twice at once. */
class OfflineDownloadScheduler(private val workManager: WorkManager) {

    /** [replace] re-creates pending work, e.g. to apply a changed Wi-Fi-only setting. */
    fun enqueue(trackId: String, wifiOnly: Boolean, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setInputData(workDataOf(TrackDownloadWorker.KEY_TRACK_ID to trackId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(
            workName(trackId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(trackId: String) {
        workManager.cancelUniqueWork(workName(trackId))
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private fun workName(trackId: String) = "offline-track:$trackId"

    private companion object {
        const val TAG = "offline-download"
    }
}
