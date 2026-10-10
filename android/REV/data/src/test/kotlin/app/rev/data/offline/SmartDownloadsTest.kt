package app.rev.data.offline

import app.rev.domain.model.OfflineKind
import app.rev.domain.model.SmartDownloadSettings
import app.rev.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartDownloadPlannerTest {
    private fun track(id: String, mb: Long) = Track(
        id, id, "Album", "al", "Artist", null, 1, 1, null, null, null, 1000, false, null, null, null, null, mb * 1_000_000,
    )

    private fun album(id: String, vararg tracks: Track) = SmartDownloadPlanner.RecentAlbum(id, id, "Artist", null, tracks.toList())

    @Test
    fun `favorites first, then recent albums, within the space limit`() {
        val plan = SmartDownloadPlanner.plan(
            SmartDownloadSettings(favorites = true, recentAlbums = 3, limitBytes = 300_000_000),
            favorites = listOf(track("f1", 50), track("f2", 50)),
            recent = listOf(
                album("a1", track("a1t1", 100)),
                album("a2", track("a2t1", 150)), // would exceed 300 MB: skipped
                album("a3", track("a3t1", 90)),
            ),
        )
        assertEquals(listOf("all", "a1", "a3"), plan.map { it.itemId })
        assertEquals(OfflineKind.Favorites, plan.first().kind)
    }

    @Test
    fun `tracks shared between favorites and albums count once`() {
        val shared = track("s", 200)
        val plan = SmartDownloadPlanner.plan(
            SmartDownloadSettings(favorites = true, recentAlbums = 1, limitBytes = 250_000_000),
            favorites = listOf(shared),
            recent = listOf(album("a1", shared)),
        )
        assertEquals(listOf("all", "a1"), plan.map { it.itemId })
    }

    @Test
    fun `respects the recent album count and does nothing when off`() {
        val recent = (1..5).map { album("a$it", track("t$it", 1)) }
        val plan = SmartDownloadPlanner.plan(SmartDownloadSettings(recentAlbums = 2), emptyList(), recent)
        assertEquals(listOf("a1", "a2"), plan.map { it.itemId })
        assertEquals(emptyList<Any>(), SmartDownloadPlanner.plan(SmartDownloadSettings(), listOf(track("f", 1)), recent))
    }
}
