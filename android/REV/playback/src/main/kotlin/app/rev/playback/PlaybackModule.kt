package app.rev.playback

import app.rev.domain.playback.EqualizerCapabilities
import app.rev.domain.playback.PlaybackQueue
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds
    abstract fun bindPlaybackQueue(controller: PlaybackController): PlaybackQueue

    @Binds
    abstract fun bindEqualizerCapabilities(capabilities: DeviceEqualizerCapabilities): EqualizerCapabilities
}
