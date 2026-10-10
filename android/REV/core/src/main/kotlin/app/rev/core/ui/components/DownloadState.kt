package app.rev.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.rev.core.R
import app.rev.domain.model.DownloadJobState

/** What the user sees for each step of acquisition. Service names never appear. */
@Composable
fun DownloadJobState.label(): String = stringResource(
    when (this) {
        DownloadJobState.REQUESTED, DownloadJobState.CHECKING -> R.string.job_requested
        DownloadJobState.ADDED_TO_LIDARR, DownloadJobState.WAITING_FOR_SOULARR -> R.string.job_waiting
        DownloadJobState.SEARCHING -> R.string.job_searching
        DownloadJobState.QUEUED -> R.string.job_queued
        DownloadJobState.DOWNLOADING -> R.string.job_downloading
        DownloadJobState.IMPORTING -> R.string.job_importing
        DownloadJobState.INDEXING -> R.string.job_indexing
        DownloadJobState.AVAILABLE -> R.string.job_available
        DownloadJobState.FAILED -> R.string.job_failed
        DownloadJobState.TIMEOUT -> R.string.job_timeout
        DownloadJobState.CANCELLED -> R.string.job_cancelled
    },
)
