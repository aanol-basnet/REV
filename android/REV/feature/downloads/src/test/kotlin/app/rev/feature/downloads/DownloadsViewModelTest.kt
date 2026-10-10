package app.rev.feature.downloads

import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.DownloadJobState
import app.rev.domain.model.Result
import app.rev.domain.repository.AcquisitionRepository
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.ServerEvents
import app.rev.domain.repository.OfflineMixRepository
import app.rev.domain.repository.OfflineRepository
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.model.Mix
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.model.SmartDownloadSettings
import kotlinx.coroutines.flow.flowOf
import app.rev.domain.model.Album
import app.rev.domain.model.OfflineCollection
import app.rev.domain.model.Playlist
import app.rev.domain.model.StorageUsage
import app.rev.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun job(id: String, state: DownloadJobState) =
        DownloadJob(id, "mb-$id", null, "Album $id", "Artist", null, state, null, null, null, 1, 1, null)

    private class FakeDownloads(jobs: List<DownloadJob>) : DownloadsRepository {
        val jobs = MutableStateFlow(jobs)
        override fun observeJobs(): Flow<List<DownloadJob>> = jobs
        override fun observeLatestJob(catalogAlbumId: String): Flow<DownloadJob?> = MutableStateFlow(null)
        override suspend fun refresh() = Result.Ok(Unit)
        var cleared = 0
        override suspend fun clearFinished(): Result<Unit, LibraryError> {
            cleared++
            return Result.Ok(Unit)
        }
    }

    private class FakeAcquisition : AcquisitionRepository {
        val requested = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        override suspend fun albumStatus(catalogAlbumId: String): Result<CatalogAlbumStatus, LibraryError> =
            Result.Err(LibraryError.NotFound)
        override suspend fun request(catalogAlbumId: String): Result<DownloadJob, LibraryError> {
            requested += catalogAlbumId
            return Result.Err(LibraryError.Unreachable)
        }
        override suspend fun cancel(jobId: String): Result<DownloadJob, LibraryError> {
            cancelled += jobId
            return Result.Err(LibraryError.Unreachable)
        }
    }

    private object NoOffline : OfflineRepository {
        override fun observeCollections(): Flow<List<OfflineCollection>> = MutableStateFlow(emptyList())
        override fun observeCollection(id: String): Flow<OfflineCollection?> = MutableStateFlow(null)
        override fun observeDownloadedTrackIds(): Flow<Set<String>> = MutableStateFlow(emptySet())
        override fun observeDownloadedTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun observeDownloadedTracks(artistId: String): Flow<List<Track>> = flowOf(emptyList())
        override fun observeStorage(): Flow<StorageUsage> = MutableStateFlow(StorageUsage(0, 0, 0))
        override val wifiOnly: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setWifiOnly(enabled: Boolean) = Unit
        override suspend fun downloadAlbum(album: Album, tracks: List<Track>) = Unit
        override suspend fun downloadPlaylist(playlist: Playlist, tracks: List<Track>) = Unit
        override suspend fun downloadTrack(track: Track) = Unit
        override suspend fun retry(collectionId: String) = Unit
        override suspend fun remove(collectionId: String) = Unit
        override suspend fun removeAll() = Unit
        override val smartDownloads: Flow<SmartDownloadSettings> = flowOf(SmartDownloadSettings())
        override suspend fun setSmartDownloads(settings: SmartDownloadSettings) = Unit
        override suspend fun syncAutomatic(wanted: List<AutomaticCollection>) = Unit
    }

    private object NoMixes : OfflineMixRepository {
        override fun observeMixes(): Flow<List<Mix>> = flowOf(emptyList())
        override suspend fun tracks(mix: Mix): List<Track> = emptyList()
    }

    private object NoQueue : PlaybackQueue {
        override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) = Unit
        override fun playNext(tracks: List<Track>) = Unit
        override fun addToQueue(tracks: List<Track>) = Unit
    }

    private object Live : ServerEvents {
        override val connected = MutableStateFlow(true)
    }

    @Test
    fun `splits jobs into in-progress and finished, and acts on them`() = runTest {
        val acquisition = FakeAcquisition()
        val downloads = FakeDownloads(
            listOf(
                job("a", DownloadJobState.DOWNLOADING),
                job("b", DownloadJobState.AVAILABLE),
                job("c", DownloadJobState.FAILED),
                job("d", DownloadJobState.WAITING_FOR_SOULARR),
            ),
        )
        val vm = DownloadsViewModel(downloads, acquisition, NoOffline, NoMixes, NoQueue, Live)
        val errors = mutableListOf<LibraryError>()
        backgroundScope.launch { vm.errors.collect { errors += it } }
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val sections = vm.state.value.content!!
        assertEquals(listOf("a", "d"), sections.inProgress.map { it.id })
        assertEquals(listOf("b", "c"), sections.finished.map { it.id })

        vm.retry(sections.finished.last())
        vm.cancel(sections.inProgress.first())
        runCurrent()
        assertEquals(listOf("mb-c"), acquisition.requested)
        assertEquals(listOf("a"), acquisition.cancelled)
        assertEquals(2, errors.size)

        vm.clearFinished()
        runCurrent()
        assertEquals(1, downloads.cleared)
    }
}

class DownloadedArtistsTest {
    @Test
    fun `groups songs by artist, most downloaded first`() {
        fun song(id: String, artist: String) = Track(
            id, id, "Album", "al", artist, "ar-$artist", null, null, null, null, "cov-$artist", 1000, false,
            null, null, null, null, null,
        )
        val artists = artistsOf(listOf(song("1", "B"), song("2", "A"), song("3", "B")))
        assertEquals(listOf("B" to 2, "A" to 1), artists.map { it.name to it.songCount })
        assertEquals("ar-B", artists.first().id)
    }
}
