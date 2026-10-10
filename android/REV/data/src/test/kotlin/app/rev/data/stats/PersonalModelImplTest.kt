package app.rev.data.stats

import app.rev.data.local.stats.LearnedExampleDao
import app.rev.data.local.stats.LearnedExampleEntity
import app.rev.data.remote.SkipModelDto
import app.rev.domain.learning.FeatureInput
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalModelImplTest {
    private class MemoryExamples : LearnedExampleDao {
        val rows = mutableListOf<LearnedExampleEntity>()
        override suspend fun insert(example: LearnedExampleEntity) {
            rows += example.copy(id = rows.size + 1L)
        }
        override suspend fun since(startedAt: Long) = rows.filter { it.startedAt > startedAt }.sortedBy { it.startedAt }
        override suspend fun count() = rows.size
        override suspend fun trim(keep: Int) {
            val kept = rows.sortedByDescending { it.startedAt }.take(keep)
            rows.retainAll(kept)
        }
        override suspend fun clear() = rows.clear()
    }

    private fun input(artist: String, output: String) = FeatureInput(
        trackId = "t-$artist", artistId = artist, albumId = null, genres = emptyList(), year = null, localHour = 12,
        weekday = 1, source = "picked", queueShape = null, shuffle = null, output = output, network = null, charging = null,
        favorite = null, previousOutcome = null, sessionSkips = null, sessionPosition = null, trackPlays = 0, trackSkips = 0,
    )

    private val dir = createTempDirectory().toFile()
    private val offline: suspend () -> Result<SkipModelDto?, LibraryError> = { Result.Err(LibraryError.Unreachable) }

    @Test
    fun `learns offline from scratch, one listen at a time`() = runTest {
        val model = PersonalModelImpl(offline, MemoryExamples(), File(dir, "m.json"))
        assertNull(model.skipChance(input("tool", "car")))
        assertEquals(0.0, model.confidence(), 0.0)
        repeat(20) { model.learn(input("tool", "car"), skipped = true, weight = 1.0, startedAt = it.toLong()) }
        model.refresh() // no server: keeps what it learned
        assertTrue(model.skipChance(input("tool", "car"))!! > 0.8)
        assertEquals(0.02, model.confidence(), 1e-9)
    }

    @Test
    fun `a newer server model is caught up with this phone's newer listens only`() = runTest {
        val examples = MemoryExamples()
        var served: SkipModelDto? = null
        val model = PersonalModelImpl({ Result.Ok(served) }, examples, File(dir, "m.json"))
        // Listened offline: 5 before the server's next model covers, 3 after.
        repeat(5) { model.learn(input("adele", "speaker"), skipped = true, weight = 1.0, startedAt = 100L + it) }
        repeat(3) { model.learn(input("adele", "speaker"), skipped = false, weight = 2.0, startedAt = 300L + it) }

        served = SkipModelDto(trainedAt = 1, dataUntil = 200, examples = 500, bias = 0.0, weights = mapOf("a:adele" to 1.0))
        model.refresh()
        // The server's 500 plus the 3 listens it hasn't seen.
        assertEquals(0.503, model.confidence(), 1e-9)

        // A restart: the cached server model, caught up again without the network.
        val restarted = PersonalModelImpl(offline, examples, File(dir, "m.json"))
        assertEquals(0.503, restarted.confidence(), 1e-9)
    }
}
