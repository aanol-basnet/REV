package app.rev.domain.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSkipModelTest {
    private val carTool = listOf("a:tool", "o:car", "a:tool|o:car")
    private val phonesTool = listOf("a:tool", "o:headphones", "a:tool|o:headphones")

    @Test
    fun `learns from each listen as it happens`() {
        val model = OnlineSkipModel(null)
        assertEquals(0.5, model.skipChance(carTool), 1e-9)
        repeat(30) {
            model.learn(carTool, skipped = true)
            model.learn(phonesTool, skipped = false)
        }
        assertTrue(model.skipChance(carTool) > 0.8)
        assertTrue(model.skipChance(phonesTool) < 0.2)
        assertEquals(60, model.examples)
    }

    @Test
    fun `starts from the server's model and keeps learning on top`() {
        val base = SkipModel(trainedAt = 1, dataUntil = 1, examples = 400, bias = 0.0, weights = mapOf("a:tool" to 3.0))
        val model = OnlineSkipModel(base)
        assertTrue(model.skipChance(listOf("a:tool")) > 0.9)
        repeat(20) { model.learn(listOf("a:tool"), skipped = false, weight = 2.0) }
        assertTrue(model.skipChance(listOf("a:tool")) < 0.9)
        assertEquals(420, model.examples)
    }
}
