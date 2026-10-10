package app.rev.domain

import app.rev.domain.model.DownloadJobState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadJobStateTest {
    @Test
    fun `only completion and failure states are terminal`() {
        val terminal = DownloadJobState.entries.filter { it.isTerminal }.toSet()
        assertEquals(
            setOf(
                DownloadJobState.AVAILABLE,
                DownloadJobState.FAILED,
                DownloadJobState.TIMEOUT,
                DownloadJobState.CANCELLED,
            ),
            terminal,
        )
    }
}
