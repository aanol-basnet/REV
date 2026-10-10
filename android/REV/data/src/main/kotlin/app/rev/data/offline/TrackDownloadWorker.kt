package app.rev.data.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.rev.data.local.offline.OfflineTrackDao
import app.rev.data.connection.RevConnectionManager
import app.rev.domain.repository.LyricsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.http.encodeURLPathPart
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads one track for offline playback from the REV stream endpoint (original file, not
 * transcoded). Resumes an interrupted download with an HTTP Range request, checks free space
 * first, reports progress to the database, and retries transient failures with backoff.
 */
@HiltWorker
class TrackDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: OfflineTrackDao,
    private val http: OkHttpClient,
    private val connection: RevConnectionManager,
    private val files: OfflineFiles,
    private val lyrics: LyricsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val row = dao.get(trackId) ?: return Result.success() // removed meanwhile
        if (row.state == "Downloaded" && File(row.localUri).isFile) return Result.success()

        // Whichever address is active now (home network or remote); a resumed download may use a
        // different one than it started on: same server, same file, so the Range request still fits.
        val server = connection.awaitActiveUrl() ?: return fail(trackId, "Not connected to a server")
        val part = files.partFile(trackId)
        val offset = if (part.isFile) part.length() else 0L
        val remaining = (row.bytesTotal - offset).coerceAtLeast(0)
        if (files.availableBytes() < remaining + STORAGE_RESERVE_BYTES) {
            return fail(trackId, "Not enough storage space")
        }

        val request = Request.Builder()
            .url("${server.value}/api/library/tracks/${trackId.encodeURLPathPart()}/stream")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        return try {
            withContext(Dispatchers.IO) { download(trackId, request, part, offset, row.format) }
        } catch (e: IOException) {
            retryOrFail(trackId, "Download interrupted")
        }
    }

    private suspend fun download(trackId: String, request: Request, part: File, offset: Long, format: String?): Result {
        http.newCall(request).execute().use { response ->
            when (response.code) {
                200, 206 -> Unit
                401 -> return fail(trackId, "Signed out")
                404 -> return fail(trackId, "No longer in your library")
                416 -> {
                    part.delete() // our partial file doesn't fit the server's copy; start over
                    return Result.retry()
                }
                in 500..599 -> return retryOrFail(trackId, "Music server unavailable")
                else -> return fail(trackId, "Download failed (${response.code})")
            }
            val append = response.code == 206 && offset > 0
            val body = response.body
            val length = body.contentLength()
            val total = if (length >= 0) (if (append) offset + length else length) else -1L
            var written = if (append) offset else 0L

            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    while (true) {
                        if (isStopped) return Result.retry() // cancelled or constraints lost; keep the .part
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = written
                            dao.updateProgress(trackId, written, total.coerceAtLeast(written))
                            setProgress(workDataOf(KEY_BYTES to written, KEY_TOTAL to total))
                        }
                    }
                }
            }
            if (total >= 0 && written != total) return retryOrFail(trackId, "Download incomplete")

            val target = files.finalFile(trackId, audioExtension(format, response.header("Content-Type")))
            if (!part.renameTo(target)) return fail(trackId, "Couldn't save the file")
            dao.markDownloaded(trackId, target.path, target.length(), System.currentTimeMillis())
            // Downloaded music should have its lyrics offline too (§33). Best effort.
            runCatching { lyrics.prefetch(trackId) }
            return Result.success()
        }
    }

    private suspend fun retryOrFail(trackId: String, reason: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(trackId, reason)

    private suspend fun fail(trackId: String, reason: String): Result {
        dao.markFailed(trackId, reason)
        return Result.failure()
    }

    companion object {
        const val KEY_TRACK_ID = "trackId"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        private const val BUFFER_BYTES = 64 * 1024
        private const val REPORT_EVERY_BYTES = 512L * 1024
        private const val MAX_ATTEMPTS = 6
        /** Never fill the device: keep this much free beyond the download. */
        private const val STORAGE_RESERVE_BYTES = 500L * 1024 * 1024
    }
}
