package app.rev.domain.learning

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenOutcomesTest {
    /** shared/model/outcome-fixtures.json, also checked by the server's tests. */
    private val fixtures = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/model/outcome-fixtures.json") }
        .first { it.exists() }

    @Test
    fun `outcomes match the server's exactly`() {
        val cases = Json.parseToJsonElement(fixtures.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(20, cases.size)
        for (case in cases) {
            val name = case.jsonObject.getValue("name").jsonPrimitive.content
            val facts = case.jsonObject.getValue("facts").jsonObject.toFacts()
            val expected = case.jsonObject.getValue("outcome").jsonPrimitive.content
            assertEquals(name, expected, ListenOutcomes.classify(facts).wire)
        }
    }

    @Test
    fun `interruptions and browsing teach nothing, a save counts more than a play`() {
        assertEquals(null, ListenOutcome.Interrupted.label)
        assertEquals(null, ListenOutcome.Browsing.label)
        assertEquals(true, ListenOutcome.Rejected.skipped)
        assertEquals(0.5, ListenOutcome.Bored.weight, 0.0)
        assertEquals(1.5, ListenOutcome.Saved.weight, 0.0)
        assertEquals(ListenOutcome.Outro, ListenOutcomes.fromWire("outro"))
    }

    private fun JsonObject.toFacts(): ListenFacts {
        fun field(name: String) = get(name)?.takeIf { it !is JsonNull }?.jsonPrimitive
        return ListenFacts(
            counted = field("counted")!!.boolean,
            durationMs = field("durationMs")!!.long,
            heardMs = field("heardMs")!!.long,
            endPositionMs = field("endPositionMs")?.long,
            endReason = field("endReason")?.content,
            pausedAtEnd = field("pausedAtEnd")!!.boolean,
            lastPauseMs = field("lastPauseMs")!!.long,
            heardSinceResumeMs = field("heardSinceResumeMs")?.long,
            systemPause = field("systemPause")!!.boolean,
            arrivedBy = field("arrivedBy")?.content,
            addedToPlaylist = field("addedToPlaylist")!!.boolean,
            curating = field("curating")!!.boolean,
        )
    }
}
