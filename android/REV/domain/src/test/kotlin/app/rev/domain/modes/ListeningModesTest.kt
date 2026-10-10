package app.rev.domain.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningModesTest {
    private val car = ModeSituation(output = "car", outputName = "Car Kit", hour = 8, weekday = 1, network = "cellular", charging = true)
    private val office = ModeSituation(output = "headphones", outputName = "Buds", hour = 10, weekday = 2, network = "wifi", charging = false)
    private val sofa = ModeSituation(output = "speaker", outputName = null, hour = 21, weekday = 5, network = "wifi", charging = false)

    private fun picks(mode: String, situation: ModeSituation, n: Int) = List(n) { ModeChoice(mode, situation.copy(hour = situation.hour + it % 2), it.toLong()) }

    @Test
    fun `guesses the mode you usually pick in that situation`() {
        val guesser = ModeGuesser(picks("driving_solo", car, 5) + picks("work", office, 5) + picks(ModeChoice.NO_MODE, sofa, 5))
        assertEquals("driving_solo", guesser.guess(car)?.first)
        assertEquals("work", guesser.guess(office.copy(hour = 11))?.first)
        // On the sofa you usually listen without a mode.
        assertNull(guesser.guess(sofa))
    }

    @Test
    fun `needs a few picks before guessing`() {
        assertNull(ModeGuesser(picks("work", office, 2)).guess(office))
        assertNull(ModeGuesser(emptyList()).guess(office))
    }

    @Test
    fun `a picked mode lasts until you listen somewhere else`() {
        val keeper = ModeKeeper()
        keeper.onUserChange(ActiveMode(BuiltInModes.DrivingFamily, ModeSource.Picked, 0), car, 0)
        // The first song is a new session, but you only just picked it.
        assertEquals(BuiltInModes.DrivingFamily, keeper.onSongStart(car, newSession = true, now = 60_000).active?.mode)
        assertEquals(BuiltInModes.DrivingFamily, keeper.onSongStart(car, newSession = false, now = 120_000).active?.mode)
        // Out of the car: back to guessing (nothing to guess from yet).
        assertNull(keeper.onSongStart(sofa, newSession = false, now = 180_000).active)
    }

    @Test
    fun `a break ends a mode picked before it`() {
        val keeper = ModeKeeper()
        keeper.onUserChange(ActiveMode(BuiltInModes.Work, ModeSource.Picked, 0), office, 0)
        keeper.onSongStart(office, newSession = true, now = 1_000)
        val nextMorning = keeper.onSongStart(office, newSession = true, now = 20 * 3_600_000L)
        assertNull(nextMorning.active)
        // A session with no guess is remembered as "no mode".
        assertEquals(ModeChoice.NO_MODE, nextMorning.remember?.modeId)
    }

    @Test
    fun `learns from picks and switches by itself`() {
        val keeper = ModeKeeper()
        repeat(3) { keeper.onUserChange(ActiveMode(BuiltInModes.DrivingSolo, ModeSource.Picked, it.toLong()), car, it.toLong()) }
        keeper.onUserChange(null, sofa, 10)
        val decision = keeper.onSongStart(car, newSession = true, now = 100)
        assertEquals(BuiltInModes.DrivingSolo to ModeSource.Auto, decision.active?.mode to decision.active?.source)
        // Turning the guess off teaches it that this isn't that mode.
        assertEquals(ModeChoice.NO_MODE, keeper.onUserChange(null, car, 200)?.modeId)
        assertEquals(true, keeper.isCurrent(null))
    }
}
