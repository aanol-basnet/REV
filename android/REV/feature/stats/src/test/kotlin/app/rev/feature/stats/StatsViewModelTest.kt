package app.rev.feature.stats

import app.rev.domain.model.Discoveries
import app.rev.domain.model.ListeningReport
import app.rev.domain.model.ListeningRhythm
import app.rev.domain.model.ListeningSessions
import app.rev.domain.model.ListeningStreaks
import app.rev.domain.model.ListeningTimeline
import app.rev.domain.model.ListeningTotals
import app.rev.domain.model.ListeningVariety
import app.rev.domain.model.ReportPeriod
import app.rev.domain.model.ReportPeriodKind
import app.rev.domain.model.Result
import app.rev.domain.model.SavedSmartPlaylist
import app.rev.domain.model.SmartPlaylist
import app.rev.domain.model.SmartPlaylistKind
import app.rev.domain.model.Track
import app.rev.domain.playback.PlaybackQueue
import app.rev.domain.model.TimelineUnit
import app.rev.domain.repository.LibraryError
import app.rev.domain.repository.StatsRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun report(kind: ReportPeriodKind, offset: Int, hasEarlier: Boolean = true, plays: Int = 1) = ListeningReport(
        period = ReportPeriod(kind, offset, "UTC", 0, 1, 1, hasEarlier),
        totals = ListeningTotals(plays, 0, 0, 0, 0, 0, 0, 0, 0, 0.0),
        previous = null,
        persona = null,
        highlights = emptyList(),
        topGenres = emptyList(),
        topArtists = emptyList(),
        topAlbums = emptyList(),
        topTracks = emptyList(),
        onRepeat = emptyList(),
        mostSkipped = emptyList(),
        discoveries = Discoveries(emptyList(), 0, 0.0, 0),
        rhythm = ListeningRhythm(List(24) { 0 }, List(7) { 0 }, List(7) { List(24) { 0 } }, null, null),
        timeline = ListeningTimeline(TimelineUnit.Day, emptyList()),
        streaks = ListeningStreaks(0, null, 0),
        sessions = ListeningSessions(0, 0, 0, null, 0),
        variety = ListeningVariety(0, 0.0, 0.0),
        trackingSince = null,
    )

    private inner class FakeStats : StatsRepository {
        val requests = mutableListOf<Triple<ReportPeriodKind, Int, String>>()
        var online = true
        val cached = mutableMapOf<ReportPeriodKind, ListeningReport>()
        var earliest = 2
        override suspend fun cachedReport(period: ReportPeriodKind) = cached[period]
        override suspend fun report(period: ReportPeriodKind, offset: Int, timeZone: String): Result<ListeningReport, LibraryError> {
            requests += Triple(period, offset, timeZone)
            return if (online) Result.Ok(report(period, offset, hasEarlier = offset < earliest)) else Result.Err(LibraryError.Unreachable)
        }

        val smart = mutableListOf(SmartPlaylist("on-repeat", SmartPlaylistKind.OnRepeat, null, 2, emptyList(), null))
        val saves = mutableListOf<String>()
        override suspend fun smartPlaylists(timeZone: String): Result<List<SmartPlaylist>, LibraryError> =
            if (online) Result.Ok(smart.toList()) else Result.Err(LibraryError.Unreachable)
        val trackRequests = mutableListOf<String>()
        override suspend fun smartPlaylistTracks(id: String, timeZone: String): Result<List<Track>, LibraryError> {
            trackRequests += id
            return if (online) Result.Ok(listOf(track("t1"), track("t2"))) else Result.Err(LibraryError.Unreachable)
        }
        override suspend fun saveSmartPlaylist(id: String, timeZone: String): Result<SavedSmartPlaylist, LibraryError> {
            if (!online) return Result.Err(LibraryError.Unreachable)
            saves += id
            return Result.Ok(SavedSmartPlaylist("pl-1", created = saves.size == 1))
        }
    }

    private fun track(id: String) = Track(
        id, "Title $id", "Album", "al-1", "Artist", "ar-1", 1, 1, 2001, "Metal", null,
        200_000, false, "flac", 1024, 44_100, 16, 1_000,
    )

    private class FakeQueue : PlaybackQueue {
        val played = mutableListOf<Pair<List<String>, Boolean>>()
        override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) { played += tracks.map { it.id } to shuffle }
        override fun playNext(tracks: List<Track>) = Unit
        override fun addToQueue(tracks: List<Track>) = Unit
    }

    @Test
    fun `year in review loads the year and plays or saves its top songs`() = runTest {
        val stats = FakeStats()
        val queue = FakeQueue()
        val vm = YearInReviewViewModel(1, stats, queue) { "UTC" }
        assertEquals(Triple(ReportPeriodKind.Year, 1, "UTC"), stats.requests.single())
        // The fake report starts at the epoch: 1970 in UTC.
        assertEquals(1970, vm.state.value.year)

        vm.playTopSongs()
        assertEquals(listOf("year-1970"), stats.trackRequests)
        assertEquals(listOf("t1", "t2"), queue.played.single().first)

        vm.saveTopSongs()
        assertEquals(listOf("year-1970"), stats.saves)
        assertEquals("pl-1", vm.state.value.saved?.playlistId)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun `smart playlists play, shuffle and save to Navidrome`() = runTest {
        val stats = FakeStats()
        val queue = FakeQueue()
        val vm = StatsViewModel(stats, queue) { "UTC" }
        val playlist = vm.smart.value.playlists.single()

        vm.playSmartPlaylist(playlist)
        vm.playSmartPlaylist(playlist, shuffle = true)
        assertEquals(listOf(listOf("t1", "t2") to false, listOf("t1", "t2") to true), queue.played)
        assertNull(vm.smart.value.starting)

        vm.saveSmartPlaylist(playlist)
        assertEquals("pl-1", vm.smart.value.playlists.single().savedPlaylistId)
        val saved = vm.smartEvents.first() as SmartPlaylistEvent.Saved
        assertTrue(saved.result.created)

        stats.online = false
        vm.saveSmartPlaylist(playlist)
        assertEquals(SmartPlaylistEvent.Failed(LibraryError.Unreachable), vm.smartEvents.first())
        assertNull(vm.smart.value.saving)
    }

    @Test
    fun `opens on this week and steps back and forth through earlier periods`() = runTest {
        val stats = FakeStats()
        val vm = StatsViewModel(stats, FakeQueue()) { "Europe/Berlin" }
        assertEquals(Triple(ReportPeriodKind.Week, 0, "Europe/Berlin"), stats.requests.single())
        assertFalse(vm.state.value.canGoLater)
        assertTrue(vm.state.value.canGoEarlier)

        vm.earlier()
        vm.earlier()
        assertEquals(2, vm.state.value.report?.period?.offset)
        assertFalse("nothing before this", vm.state.value.canGoEarlier)
        vm.earlier()
        assertEquals(3, stats.requests.size)

        vm.later()
        assertEquals(1, vm.state.value.offset)
        vm.selectPeriod(ReportPeriodKind.Year)
        assertEquals(Triple(ReportPeriodKind.Year, 0, "Europe/Berlin"), stats.requests.last())
        assertEquals(ReportPeriodKind.Year, vm.state.value.report?.period?.kind)
    }

    @Test
    fun `offline, the current period shows the saved report with the error`() = runTest {
        val stats = FakeStats().apply {
            online = false
            cached[ReportPeriodKind.Week] = report(ReportPeriodKind.Week, 0, plays = 42)
        }
        val vm = StatsViewModel(stats, FakeQueue()) { "UTC" }
        assertEquals(42, vm.state.value.report?.totals?.plays)
        assertEquals(LibraryError.Unreachable, vm.state.value.error)

        // An older period has nothing saved: an error, not last week's numbers under the wrong title.
        stats.online = true
        vm.refresh()
        vm.earlier()
        stats.online = false
        vm.earlier()
        assertNull(vm.state.value.report)
        assertEquals(LibraryError.Unreachable, vm.state.value.error)
    }

    @Test
    fun `formats ranges, hours and changes`() {
        val f = StatsFormat("UTC", Locale.US)
        val day = 86_400_000L
        val oct5 = java.time.LocalDate.of(2026, 10, 5).toEpochDay() * day
        assertEquals("5 – 11 Oct 2026", f.range(oct5, oct5 + 7 * day))
        assertEquals("28 Sep – 4 Oct 2026", f.range(oct5 - 7 * day, oct5))
        assertEquals("October 2026", f.month(oct5))
        assertEquals("Monday", f.weekday(0))
        assertEquals("34%", f.percent(0.344))
        assertEquals(50, f.change(150, 100))
        assertNull(f.change(10, 0))
    }
}
