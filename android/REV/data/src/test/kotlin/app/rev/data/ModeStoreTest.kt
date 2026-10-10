package app.rev.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rev.domain.modes.BuiltInModes
import app.rev.domain.modes.ModeLean
import app.rev.domain.modes.ModeSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeStoreTest {
    @Test
    fun `keeps your modes and the one in use`() = runTest {
        val dir = createTempDirectory().toFile()
        val store = ModeStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "modes.preferences_pb") }) { 5 }
        assertEquals(BuiltInModes.all, store.modes.first())
        assertNull(store.active.first())

        val gym = store.addCustom("  Gym  ", ModeLean.Discover)
        assertEquals("Gym", gym.name)
        assertEquals(BuiltInModes.all + gym, store.modes.first())

        store.pick(gym.id)
        assertEquals(gym to ModeSource.Picked, store.active.first()?.let { it.mode to it.source })
        store.setAuto(BuiltInModes.Work)
        assertEquals(BuiltInModes.Work to ModeSource.Auto, store.active.first()?.let { it.mode to it.source })

        store.pick(gym.id)
        store.removeCustom(gym.id)
        assertNull(store.active.first())
        assertEquals(BuiltInModes.all, store.modes.first())
    }
}
