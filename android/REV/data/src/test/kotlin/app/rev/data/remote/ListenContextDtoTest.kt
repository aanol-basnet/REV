package app.rev.data.remote

import app.rev.domain.playback.AudioOutput
import app.rev.domain.playback.EndReason
import app.rev.domain.playback.ListenBehaviour
import app.rev.domain.playback.ListenContext
import app.rev.domain.playback.QueueSource
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenContextDtoTest {
    @Test
    fun `every value goes over the wire, including false and zero`() {
        val context = ListenContext(
            source = QueueSource.Autoplay,
            shuffle = false,
            queuePosition = 0,
            endReason = EndReason.NewQueue,
            output = AudioOutput.Headphones,
            behaviour = ListenBehaviour(pauses = 0, favoritedDuring = false),
            predictedScore = 0.123456,
        )
        val json = RevJson.encodeToJsonElement(ListenContextDto.serializer(), context.toDto()).jsonObject
        assertEquals("1", json.getValue("v").jsonPrimitive.content)
        assertEquals("autoplay", json.getValue("source").jsonPrimitive.content)
        assertEquals("false", json.getValue("shuffle").jsonPrimitive.content)
        assertEquals("0", json.getValue("queuePosition").jsonPrimitive.content)
        assertEquals("new_queue", json.getValue("endReason").jsonPrimitive.content)
        assertEquals("headphones", json.getValue("output").jsonPrimitive.content)
        assertEquals("false", json.getValue("favoritedDuring").jsonPrimitive.content)
        assertEquals("0.123", json.getValue("predictedScore").jsonPrimitive.content)
        // Unknowns are left out rather than sent as null.
        assertTrue("outputName" !in json)
    }
}
