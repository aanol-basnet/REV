package app.rev.domain.model

data class LyricLine(val startMs: Long, val text: String)

sealed interface Lyrics {
    /** Time-synced lines (already parsed by the server). */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    data class Plain(val text: String) : Lyrics

    data object Instrumental : Lyrics

    data object None : Lyrics
}

/** Which synced line is being sung at a playback position. */
object LyricsSync {
    /**
     * Index of the last line that has started at [positionMs], or -1 before the first line.
     * Binary search: called several times a second during playback.
     */
    fun currentLine(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
