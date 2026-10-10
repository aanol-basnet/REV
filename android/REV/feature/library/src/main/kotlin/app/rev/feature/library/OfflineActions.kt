package app.rev.feature.library

import app.rev.domain.repository.OfflineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Ids of tracks downloaded to this device, for the ✓ marks in track lists. */
internal fun OfflineRepository.downloadedIds(scope: CoroutineScope): StateFlow<Set<String>> =
    observeDownloadedTrackIds().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())
