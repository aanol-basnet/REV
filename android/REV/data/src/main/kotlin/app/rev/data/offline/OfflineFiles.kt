package app.rev.data.offline

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Where downloaded audio lives: app-private storage (no permissions, invisible to other apps,
 * removed with the app). Files are named by track id; ".part" holds an unfinished download so it
 * can resume.
 */
class OfflineFiles(context: Context) {
    val directory: File = File(context.filesDir, "offline").apply { mkdirs() }

    fun partFile(trackId: String): File = File(directory, "${safe(trackId)}.part")

    fun finalFile(trackId: String, extension: String): File = File(directory, "${safe(trackId)}.$extension")

    /** Deletes every file belonging to a track, finished or not. */
    fun delete(trackId: String) {
        val prefix = "${safe(trackId)}."
        directory.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    fun availableBytes(): Long = StatFs(directory.path).availableBytes

    private fun safe(trackId: String) = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** File extension for a downloaded track, from its known format or the response content type. */
internal fun audioExtension(format: String?, contentType: String?): String {
    format?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }?.let { return it }
    return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "audio"
    }
}
