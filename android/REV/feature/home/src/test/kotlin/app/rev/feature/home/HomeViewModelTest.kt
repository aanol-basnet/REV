package app.rev.feature.home

import app.rev.domain.model.ConnectionState
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.HomeFeed
import app.rev.domain.model.Mix
import app.rev.domain.model.MixKind
import app.rev.domain.model.Result
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.modes.ActiveMode
import app.rev.domain.modes.BuiltInModes
import app.rev.domain.modes.ListeningMode
import app.rev.domain.modes.ModeLean
import app.rev.domain.modes.ModeRepository
import app.rev.domain.repository.ConnectionRepository
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.HomeRepository
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.OfflineMixRepository
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val mix = Mix("artist:ar-1", MixKind.Artist, "Tool", listOf("A Perfect Circle"), emptyList())
    private val feed = HomeFeed(1, emptyList(), listOf(mix), emptyList(), emptyList())

    private class FakeHome(private val feed: HomeFeed) : HomeRepository {
        val stored = MutableStateFlow<HomeFeed?>(null)
        val refreshes = mutableListOf<Boolean>()
        var mixResult: Result<List<Track>, LibraryError> = Result.Ok(listOf(track("t1")))
        override fun observeFeed(): Flow<HomeFeed?> = stored
        override suspend fun refresh(force: Boolean): Result<Unit, LibraryError> {
            refreshes += force
            stored.value = feed
            return Result.Ok(Unit)
        }
        override suspend fun mixTracks(mixId: String) = mixResult
    }

    private object NoDownloads : DownloadsRepository {
        override fun observeJobs(): Flow<List<DownloadJob>> = flowOf(emptyList())
        override fun observeLatestJob(catalogAlbumId: String): Flow<DownloadJob?> = flowOf(null)
        override suspend fun refresh() = Result.Ok(Unit)
        override suspend fun clearFinished() = Result.Ok(Unit)
    }

    private class FakeOfflineMixes : OfflineMixRepository {
        var tracks: List<Track> = emptyList()
        override fun observeMixes(): Flow<List<Mix>> = flowOf(emptyList())
        override suspend fun tracks(mix: Mix) = tracks
    }

    private class FakeConnection : ConnectionRepository {
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
        override fun recheck() = Unit
    }

    private fun viewModel(home: HomeRepository, queue: PlaybackQueue = FakeQueue(), offline: OfflineMixRepository = FakeOfflineMixes()) =
        HomeViewModel(home, NoDownloads, queue, offline, FakeConnection(), NoModes)

    private object NoModes : ModeRepository {
        override val modes: Flow<List<ListeningMode>> = flowOf(BuiltInModes.all)
        override val active: Flow<ActiveMode?> = flowOf(null)
        override suspend fun pick(modeId: String?) = Unit
        override suspend fun setAuto(mode: ListeningMode?) = Unit
        override suspend fun addCustom(name: String, lean: ModeLean) = ListeningMode("custom:x", name, lean)
        override suspend fun removeCustom(id: String) = Unit
    }

    private class FakeQueue : PlaybackQueue {
        val played = mutableListOf<List<Track>>()
        override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
            played += tracks
        }
        override fun playNext(tracks: List<Track>) = Unit
        override fun addToQueue(tracks: List<Track>) = Unit
    }

    @Test
    fun `loads the feed, and pull to refresh asks the server to rebuild`() = runTest {
        val home = FakeHome(feed)
        val vm = viewModel(home)
        backgroundScope.launch { vm.feed.collect {} }
        runCurrent()
        assertEquals(feed, vm.feed.value.content)
        assertEquals(listOf(false), home.refreshes)

        vm.refresh()
        runCurrent()
        assertEquals(listOf(false, true), home.refreshes)
    }

    @Test
    fun `playing a mix queues its tracks, and a failure is reported instead`() = runTest {
        val home = FakeHome(feed)
        val queue = FakeQueue()
        val vm = viewModel(home, queue)
        val errors = mutableListOf<LibraryError>()
        backgroundScope.launch { vm.errors.collect { errors += it } }

        vm.playMix(mix)
        runCurrent()
        assertEquals(listOf(listOf(track("t1"))), queue.played)
        assertNull(vm.startingMix.value)

        home.mixResult = Result.Err(LibraryError.Unreachable)
        vm.playMix(mix)
        runCurrent()
        assertEquals(1, queue.played.size)
        assertEquals(listOf(LibraryError.Unreachable), errors)
    }

    @Test
    fun `an unreachable server mix plays from downloads when any fit`() = runTest {
        val home = FakeHome(feed).apply { mixResult = Result.Err(LibraryError.Unreachable) }
        val queue = FakeQueue()
        val offline = FakeOfflineMixes().apply { tracks = listOf(track("local")) }
        val vm = viewModel(home, queue, offline)
        val errors = mutableListOf<LibraryError>()
        val fallbacks = mutableListOf<Unit>()
        backgroundScope.launch { vm.errors.collect { errors += it } }
        backgroundScope.launch { vm.playedFromDownloads.collect { fallbacks += it } }

        vm.playMix(mix)
        runCurrent()
        assertEquals(listOf(listOf(track("local"))), queue.played)
        assertEquals(1, fallbacks.size)
        assertEquals(emptyList<LibraryError>(), errors)
    }

    @Test
    fun `greets by time of day`() {
        val vm = viewModel(FakeHome(feed))
        assertEquals(Greeting.Morning, vm.greeting(LocalTime.of(8, 0)))
        assertEquals(Greeting.Evening, vm.greeting(LocalTime.of(20, 0)))
        assertEquals(Greeting.Night, vm.greeting(LocalTime.of(2, 0)))
    }
}

private fun track(id: String) = Track(
    id, "Track $id", "Album", "al", "Tool", "ar-1", 1, 1, null, null, null, 1000, false,
    "flac", null, null, null, null,
)
