package app.rev.domain.learning

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipFeaturesTest {
    /** shared/model/feature-fixtures.json, also checked by the server's tests. */
    private val fixtures = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/model/feature-fixtures.json") }
        .first { it.exists() }

    @Test
    fun `features match the server's exactly`() {
        val cases = Json.parseToJsonElement(fixtures.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(3, cases.size)
        for (case in cases) {
            val input = case.jsonObject.getValue("input").jsonObject
            val expected = case.jsonObject.getValue("features").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, SkipFeatures.of(input.toFeatureInput()))
        }
    }

    private fun JsonObject.toFeatureInput(): FeatureInput {
        fun field(name: String): JsonElement? = get(name)?.takeIf { it !is JsonNull }
        fun str(name: String) = field(name)?.jsonPrimitive?.content
        fun int(name: String) = field(name)?.jsonPrimitive?.int
        fun bool(name: String) = field(name)?.jsonPrimitive?.boolean
        return FeatureInput(
            trackId = str("trackId")!!,
            artistId = str("artistId"),
            albumId = str("albumId"),
            genres = getValue("genres").jsonArray.map { it.jsonPrimitive.content },
            year = int("year"),
            localHour = int("localHour")!!,
            weekday = int("weekday")!!,
            source = str("source"),
            queueShape = str("queueShape"),
            shuffle = bool("shuffle"),
            output = str("output"),
            network = str("network"),
            charging = bool("charging"),
            favorite = bool("favorite"),
            previousOutcome = str("previousOutcome"),
            sessionSkips = int("sessionSkips"),
            sessionPosition = int("sessionPosition"),
            trackPlays = int("trackPlays")!!,
            trackSkips = int("trackSkips")!!,
        )
    }
}
