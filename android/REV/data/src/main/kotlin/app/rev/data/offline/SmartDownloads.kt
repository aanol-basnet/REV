package app.rev.data.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import app.rev.domain.model.AlbumListType
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.Result
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.model.Track
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.repository.LibraryRepository
import app.rev.domain.repository.OfflineRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Decides what smart downloads should keep: favorite tracks first, then the most recently played
 * albums, until the space limit is reached. Pure, so the policy is testable.
 */
object SmartDownloadPlanner {
    data class RecentAlbum(val id: String, val title: String, val artist: String, val coverArtId: String?, val tracks: List<Track>)

    fun plan(settings: SmartDownloadSettings, favorites: List<Track>, recent: List<RecentAlbum>): List<AutomaticCollection> {
        if (!settings.enabled) return emptyList()
        val planned = mutableListOf<AutomaticCollection>()
        val counted = mutableSetOf<String>()
        var bytes = 0L
        fun fits(tracks: List<Track>): Boolean {
            val added = tracks.filter { it.id !in counted }.sumOf { it.sizeBytes ?: ESTIMATED_TRACK_BYTES }
            if (bytes + added > settings.limitBytes) return false
            bytes += added
            tracks.forEach { counted += it.id }
            return true
        }
        if (settings.favorites && favorites.isNotEmpty()) {
            // Favorites are kept as far as space allows, in the order Navidrome lists them.
            val kept = favorites.takeWhile { fits(listOf(it)) }
            if (kept.isNotEmpty()) planned += AutomaticCollection(OfflineKind.Favorites, "all", "Favorites", null, null, kept)
        }
        for (album in recent.take(settings.recentAlbums)) {
            if (album.tracks.isEmpty() || !fits(album.tracks)) continue
            planned += AutomaticCollection(OfflineKind.Album, album.id, album.title, album.artist, album.coverArtId, album.tracks)
        }
        return planned
    }

    /** Assumed size of a track whose size the server didn't report (a typical FLAC track). */
    const val ESTIMATED_TRACK_BYTES = 30L * 1024 * 1024
}

/** Refreshes favorites and recently played from the server and applies the smart download plan. */
@HiltWorker
class SmartDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult {
        val settings = offline.smartDownloads.first()
        if (!settings.enabled) {
            offline.syncAutomatic(emptyList())
            return WorkResult.success()
        }
        // Without fresh data from the server, keep what's there and try again later.
        if (settings.favorites && library.refreshFavorites() is Result.Err) return WorkResult.retry()
        if (settings.recentAlbums > 0 && library.refreshAlbumList(AlbumListType.Recent) is Result.Err) {
            return WorkResult.retry()
        }
        val favorites = if (settings.favorites) library.observeFavorites().first().tracks else emptyList()
        val recent = library.observeAlbumList(AlbumListType.Recent).first().take(settings.recentAlbums).map { album ->
            library.refreshAlbum(album.id)
            val tracks = library.observeAlbum(album.id).first()?.tracks.orEmpty()
            SmartDownloadPlanner.RecentAlbum(album.id, album.name, album.artist, album.coverArtId, tracks)
        }
        offline.syncAutomatic(SmartDownloadPlanner.plan(settings, favorites, recent))
        return WorkResult.success()
    }

    companion object {
        private const val PERIODIC = "smart-downloads"
        private const val NOW = "smart-downloads-now"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        /** Twice a day; kept if already scheduled. */
        fun schedule(workManager: WorkManager, wifiOnly: Boolean) {
            workManager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS).setConstraints(constraints(wifiOnly)).build(),
            )
        }

        /** Right away, e.g. after the settings changed. */
        fun runNow(workManager: WorkManager, wifiOnly: Boolean) {
            workManager.enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmartDownloadWorker>().setConstraints(constraints(wifiOnly)).build(),
            )
        }
    }
}

/** Keeps the smart download schedule in step with the settings. */
class SmartDownloadScheduler(context: Context, private val settings: OfflineSettings) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun schedule() = SmartDownloadWorker.schedule(workManager, settings.wifiOnlyNow())

    suspend fun runNow() = SmartDownloadWorker.runNow(workManager, settings.wifiOnlyNow())
}
