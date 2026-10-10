package app.rev.ui

import android.content.Context
import app.rev.core.ui.components.ArtworkSize
import app.rev.core.ui.components.CoverArt
import app.rev.domain.repository.ArtworkPrefetcher
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Loads artwork for downloaded music into Coil's disk cache at the sizes the app shows, so covers
 * appear offline too (lists, album/player screens, notification).
 */
class CoilArtworkPrefetcher(private val context: Context) : ArtworkPrefetcher {
    override fun prefetch(coverArtIds: Collection<String>) {
        val loader = SingletonImageLoader.get(context)
        for (id in coverArtIds.toSet()) {
            for (size in SIZES) {
                loader.enqueue(ImageRequest.Builder(context).data(CoverArt(id, size)).build())
            }
        }
    }

    private companion object {
        // 600 matches the notification and lock-screen artwork requests, which share the same URL.
        val SIZES = listOf(ArtworkSize.THUMBNAIL, ArtworkSize.CARD, ArtworkSize.LARGE, 600)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ArtworkModule {
    @Provides
    @Singleton
    fun provideArtworkPrefetcher(@ApplicationContext context: Context): ArtworkPrefetcher = CoilArtworkPrefetcher(context)
}
