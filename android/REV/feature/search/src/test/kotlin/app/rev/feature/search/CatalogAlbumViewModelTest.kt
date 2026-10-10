package app.rev.feature.search

import androidx.lifecycle.SavedStateHandle
import app.rev.domain.model.AlbumAvailability
import app.rev.domain.model.CatalogAlbumStatus
import app.rev.domain.model.DownloadJob
import app.rev.domain.model.DownloadJobState
import app.rev.domain.model.Result
import app.rev.domain.repository.AcquisitionRepository
import app.rev.domain.repository.DownloadsRepository
import app.rev.domain.repository.ServerEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import app.rev.domain.repository.LibraryError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogAlbumViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val mbid = "aaaaaaaa-0000-4000-8000-000000000005"

    private fun job(state: DownloadJobState) = DownloadJob(
        "job-1", mbid, null, "Opiate", "Tool", 1992, state, null, null, null, 1, 1, null,
    )

    private fun status(availability: AlbumAvailability, job: DownloadJob? = null) =
        CatalogAlbumStatus(mbid, "Opiate", "Tool", 1992, "EP", 6, availability, null, job)

    private class FakeAcquisition(var status: CatalogAlbumStatus) : AcquisitionRepository {
        var statusCalls = 0
        val requests = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        var requestResult: ((String) -> Result<DownloadJob, LibraryError>)? = null

        override suspend fun albumStatus(catalogAlbumId: String): Result<CatalogAlbumStatus, LibraryError> {
            statusCalls++
            return Result.Ok(status)
        }

        override suspend fun request(catalogAlbumId: String): Result<DownloadJob, LibraryError> {
            requests += catalogAlbumId
            return requestResult?.invoke(catalogAlbumId) ?: Result.Ok(status.job!!)
        }

        override suspend fun cancel(jobId: String): Result<DownloadJob, LibraryError> {
            cancels += jobId
            return Result.Ok(status.job!!)
        }
    }

    private val created = mutableListOf<CatalogAlbumViewModel>()

    private class FakeDownloads : DownloadsRepository {
        val latest = MutableStateFlow<DownloadJob?>(null)
        override fun observeJobs(): Flow<List<DownloadJob>> = latest.map { listOfNotNull(it) }
        override fun observeLatestJob(catalogAlbumId: String): Flow<DownloadJob?> = latest
        override suspend fun refresh() = Result.Ok(Unit)
        var cleared = 0
        override suspend fun clearFinished(): Result<Unit, LibraryError> {
            cleared++
            return Result.Ok(Unit)
        }
    }

    private class FakeEvents(connected: Boolean) : ServerEvents {
        override val connected = MutableStateFlow(connected)
    }

    private val downloads = FakeDownloads()
    private var events = FakeEvents(connected = false)

    private fun vm(repo: FakeAcquisition) =
        CatalogAlbumViewModel(SavedStateHandle(mapOf("catalogAlbumId" to mbid)), repo, downloads, events)
            .also { created += it }

    /**
     * Status polling loops until a request finishes; cancel the ViewModels at the end of each test
     * as the framework would, or runTest waits on the loop forever.
     */
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            created.forEach { it.viewModelScope.cancel() }
        }
    }

    @Test
    fun `download requests the album and shows the new state`() = vmTest {
        val repo = FakeAcquisition(status(AlbumAvailability.MISSING))
        val vm = vm(repo)
        runCurrent()
        assertEquals(AlbumAvailability.MISSING, vm.state.value.status?.availability)

        repo.requestResult = {
            repo.status = status(AlbumAvailability.REQUESTED, job(DownloadJobState.ADDED_TO_LIDARR))
            Result.Ok(job(DownloadJobState.ADDED_TO_LIDARR))
        }
        vm.download()
        vm.download() // double tap while busy is ignored
        runCurrent()

        assertEquals(listOf(mbid), repo.requests)
        assertEquals(DownloadJobState.ADDED_TO_LIDARR, vm.state.value.status?.job?.state)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `polls only while a request is in progress`() = vmTest {
        val repo = FakeAcquisition(status(AlbumAvailability.REQUESTED, job(DownloadJobState.SEARCHING)))
        val vm = vm(repo)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val initial = repo.statusCalls

        advanceTimeBy(30_001)
        assertEquals(initial + 1, repo.statusCalls)

        repo.status = status(AlbumAvailability.AVAILABLE, job(DownloadJobState.AVAILABLE)).copy(libraryAlbumId = "al-op")
        advanceTimeBy(30_001)
        val afterDone = repo.statusCalls
        advanceTimeBy(30_000)
        assertEquals(afterDone, repo.statusCalls)
        assertEquals("al-op", vm.state.value.status?.libraryAlbumId)
    }

    @Test
    fun `with live updates, job changes drive the page instead of polling`() = vmTest {
        events = FakeEvents(connected = true)
        val repo = FakeAcquisition(status(AlbumAvailability.REQUESTED, job(DownloadJobState.SEARCHING)))
        val vm = vm(repo)
        runCurrent()
        val initial = repo.statusCalls

        advanceTimeBy(120_000)
        assertEquals("no polling while live", initial, repo.statusCalls)

        repo.status = status(AlbumAvailability.AVAILABLE, job(DownloadJobState.AVAILABLE)).copy(libraryAlbumId = "al-op")
        downloads.latest.value = job(DownloadJobState.DOWNLOADING)
        runCurrent()
        downloads.latest.value = job(DownloadJobState.AVAILABLE)
        runCurrent()
        assertEquals("al-op", vm.state.value.status?.libraryAlbumId)
    }

    @Test
    fun `failed requests are reported`() = vmTest {
        val repo = FakeAcquisition(status(AlbumAvailability.MISSING)).apply {
            requestResult = { Result.Err(LibraryError.MusicServerUnavailable("down")) }
        }
        val vm = vm(repo)
        val errors = mutableListOf<LibraryError>()
        backgroundScope.launch { vm.errors.collect { errors += it } }
        runCurrent()
        vm.download()
        runCurrent()
        assertEquals(listOf<LibraryError>(LibraryError.MusicServerUnavailable("down")), errors)
    }

    @Test
    fun `cancel targets the active job`() = vmTest {
        val repo = FakeAcquisition(status(AlbumAvailability.REQUESTED, job(DownloadJobState.QUEUED)))
        val vm = vm(repo)
        runCurrent()
        vm.cancel()
        runCurrent()
        assertEquals(listOf("job-1"), repo.cancels)
    }
}
