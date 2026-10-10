package app.rev.feature.settings

import app.rev.domain.playback.EqualizerBand
import app.rev.domain.playback.EqualizerInfo
import app.rev.domain.playback.EqualizerPreset
import app.rev.domain.playback.EqualizerSettings
import app.rev.domain.playback.PlaybackSettings
import app.rev.domain.playback.PlaybackSettingsRepository
import app.rev.domain.playback.ReplayGainMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EqualizerViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeSettings : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun setReplayGain(mode: ReplayGainMode) = Unit
        override suspend fun setCrossfadeSeconds(seconds: Int) = Unit
        override suspend fun setAutoplay(enabled: Boolean) = Unit
        override suspend fun setFollowSkips(enabled: Boolean) = Unit
        override suspend fun setEqualizer(equalizer: EqualizerSettings) = settings.update { it.copy(equalizer = equalizer) }
    }

    private val rock = EqualizerPreset("Rock", listOf(500, 300, -100, 300, 500))
    private val info = EqualizerInfo(
        bands = listOf(60, 230, 910, 3600, 14000).map(::EqualizerBand),
        minLevelMb = -1500,
        maxLevelMb = 1500,
        presets = listOf(rock),
        bassBoostSupported = true,
    )

    @Test
    fun `presets, hand-tuned bands and reset are saved`() = runTest {
        val settings = FakeSettings()
        val vm = EqualizerViewModel(settings) { info }
        assertEquals(info, vm.state.value.info)
        assertFalse(vm.state.value.checking)

        vm.setEnabled(true)
        vm.selectPreset(rock)
        assertEquals(EqualizerSettings(enabled = true, bandLevelsMb = rock.bandLevelsMb, preset = "Rock"), settings.settings.value.equalizer)

        // Touching a band makes it custom and keeps the others.
        vm.setBand(2, 600)
        assertEquals(listOf(500, 300, 600, 300, 500), settings.settings.value.equalizer.bandLevelsMb)
        assertNull(settings.settings.value.equalizer.preset)

        vm.setBassBoost(1500)
        assertEquals(1000, settings.settings.value.equalizer.bassBoost)

        vm.reset()
        assertEquals(EqualizerSettings(enabled = true), settings.settings.value.equalizer)
    }

    @Test
    fun `a band set from flat fills the others with 0 dB`() = runTest {
        val settings = FakeSettings()
        val vm = EqualizerViewModel(settings) { info }
        vm.setBand(0, -300)
        assertEquals(listOf(-300, 0, 0, 0, 0), settings.settings.value.equalizer.bandLevelsMb)
    }

    @Test
    fun `a phone without an equalizer says so`() = runTest {
        val vm = EqualizerViewModel(FakeSettings()) { null }
        assertNull(vm.state.value.info)
        assertFalse(vm.state.value.checking)
    }
}
