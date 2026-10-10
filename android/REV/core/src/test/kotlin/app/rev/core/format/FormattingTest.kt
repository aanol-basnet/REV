package app.rev.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test
    fun `track durations`() {
        assertEquals("0:00", formatTrackDuration(0))
        assertEquals("8:36", formatTrackDuration(516_000))
        assertEquals("1:02:15", formatTrackDuration(3_735_000))
    }

    @Test
    fun `album durations round to minutes`() {
        assertEquals("1 hr 18 min", formatTotalDuration(4_705_500))
        assertEquals("42 min", formatTotalDuration(2_520_000))
        assertEquals("2 hr", formatTotalDuration(7_200_000))
        assertEquals("2 hr 5 min", formatTotalDuration(7_500_000))
    }

    @Test
    fun `file sizes`() {
        assertEquals("66.0 MB", formatFileSize(66_000_000))
        assertEquals("1.50 GB", formatFileSize(1_500_000_000))
    }
}
