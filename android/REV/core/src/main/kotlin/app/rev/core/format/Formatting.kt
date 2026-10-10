package app.rev.core.format

import java.util.Locale

/** 3:07, or 1:02:15 for long tracks. */
fun formatTrackDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** "78 min", or "2 hr 5 min" for long albums. */
fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ((ms + 30_000) / 60_000).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0L -> "$hours hr"
        else -> "$hours hr $minutes min"
    }
}

/** 66.2 MB */
fun formatFileSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) String.format(Locale.ROOT, "%.2f GB", mb / 1000) else String.format(Locale.ROOT, "%.1f MB", mb)
}
