package app.rev.feature.search

import app.rev.domain.model.Result
import app.rev.domain.model.SearchResults
import app.rev.domain.model.SourceStatus
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val track = Track("t1", "Schism", "Lateralus", "al-1", "Tool", "ar-1", 6, 1, 2001, null, null, 403_000, false, null, null, null, null, null)

    private class FakeSearch : SearchRepository {
        val queries = mutableListOf<String>()
        var result: Result<SearchResults, LibraryError>? = null
        override suspend fun search(query: String): Result<SearchResults, LibraryError> {
            queries += query
            return result ?: Result.Ok(SearchResults(query, emptyList(), emptyList(), emptyList(), SourceStatus.Ok, SourceStatus.Ok))
        }
        var local: SearchResults? = null
        override suspend fun searchOnDevice(query: String): SearchResults =
            local ?: SearchResults(query, emptyList(), emptyList(), emptyList(), SourceStatus.Unavailable, SourceStatus.NotConfigured, onDevice = true)
    }

    private class FakeQueue : PlaybackQueue {
        val played = mutableListOf<Pair<List<String>, Int>>()
        override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
            played += tracks.map { it.id } to startIndex
        }
        override fun playNext(tracks: List<Track>) = Unit
        override fun addToQueue(tracks: List<Track>) = Unit
    }

    @Test
    fun `debounces typing and only searches the settled query`() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = SearchViewModel(search, FakeQueue())
        backgroundScope.launch { vm.state.collect {} }

        vm.onQueryChange("t")
        advanceTimeBy(100)
        vm.onQueryChange("to")
        advanceTimeBy(100)
        vm.onQueryChange("tool ")
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("tool"), search.queries)
        assertTrue(vm.state.value is SearchState.Results)
    }

    @Test
    fun `clearing the query returns to idle immediately`() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeSearch(), FakeQueue())
        backgroundScope.launch { vm.state.collect {} }
        vm.onQueryChange("tool")
        advanceTimeBy(400)
        vm.onQueryChange("")
        runCurrent()
        assertEquals(SearchState.Idle, vm.state.value)
    }

    @Test
    fun `failures can be retried`() = runTest(dispatcher) {
        val search = FakeSearch().apply { result = Result.Err(LibraryError.Unreachable) }
        val vm = SearchViewModel(search, FakeQueue())
        backgroundScope.launch { vm.state.collect {} }
        vm.onQueryChange("tool")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(SearchState.Failed(LibraryError.Unreachable), vm.state.value)

        search.result = null
        vm.retry()
        runCurrent()
        assertTrue(vm.state.value is SearchState.Results)
        assertEquals(listOf("tool", "tool"), search.queries)
    }

    @Test
    fun `tapping a track result plays the result tracks from there`() = runTest(dispatcher) {
        val search = FakeSearch().apply {
            result = Result.Ok(
                SearchResults("tool", emptyList(), emptyList(), listOf(track, track.copy(id = "t2")), SourceStatus.Ok, SourceStatus.Ok),
            )
        }
        val queue = FakeQueue()
        val vm = SearchViewModel(search, queue)
        backgroundScope.launch { vm.state.collect {} }
        vm.onQueryChange("tool")
        advanceTimeBy(400)
        runCurrent()

        vm.playTracks(1)
        assertEquals(listOf(listOf("t1", "t2") to 1), queue.played)
    }

    @Test
    fun `without a server, searches the device, and reports the error when nothing is there`() = runTest(dispatcher) {
        val search = FakeSearch().apply { result = Result.Err(LibraryError.Unreachable) }
        val vm = SearchViewModel(search, FakeQueue())
        backgroundScope.launch { vm.state.collect {} }

        vm.onQueryChange("tool")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(SearchState.Failed(LibraryError.Unreachable), vm.state.value)

        search.local = SearchResults("schism", emptyList(), emptyList(), listOf(track), SourceStatus.Unavailable, SourceStatus.NotConfigured, onDevice = true)
        vm.onQueryChange("schism")
        advanceTimeBy(400)
        runCurrent()
        val results = (vm.state.value as SearchState.Results).results
        assertTrue(results.onDevice)
        assertEquals(listOf("t1"), results.tracks.map { it.id })
    }
}
