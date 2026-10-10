package app.rev.data.offline

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.rev.data.FakeSessionStore
import app.rev.data.local.RevDatabase
import app.rev.data.local.offline.OfflineTrackEntity
import app.rev.data.playback.authenticatedMediaHttpClient
import app.rev.data.remote.RevHttpClient
import app.rev.data.server.ServerConfigStore
import app.rev.data.connection.NetworkMonitor
import app.rev.data.connection.RevConnectionManager
import app.rev.domain.model.ServerInfo
import app.rev.domain.repository.ProbeError
import kotlinx.coroutines.flow.MutableStateFlow
import app.rev.data.session
import app.rev.domain.model.Album
import app.rev.domain.model.Lyrics
import app.rev.domain.repository.LyricsRepository
import app.rev.domain.model.OfflineKind
import app.rev.domain.model.OfflineState
import app.rev.domain.repository.AutomaticCollection
import app.rev.domain.model.Playlist
import app.rev.domain.model.Result
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerUrl
import app.rev.domain.model.Track
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDownloadsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, RevDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.offlineTrackDao()
    private val files = OfflineFiles(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetched = mutableListOf<String>()
    private lateinit var workManager: WorkManager
    private lateinit var repo: OfflineRepositoryImpl

    @Before
    fun setUp() {
        // Robolectric reports no free space unless told otherwise; give the download dir ~4 GB.
        ShadowStatFs.registerStats(files.directory, 1_000_000, 1_000_000, 1_000_000)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        val settings = OfflineSettings(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "offline-test.preferences_pb") },
        )
        repo = OfflineRepositoryImpl(dao, OfflineDownloadScheduler(workManager), files, settings, { prefetched += it })
    }

    @After
    fun tearDown() {
        db.close()
        files.deleteAll()
        scope.cancel()
        File(context.cacheDir, "offline-test.preferences_pb").delete()
    }

    private fun track(id: String, size: Long = 100) = Track(
        id, "Track $id", "Lateralus", "al-1", "Tool", "ar-1", 1, 1, 2001, null, "cov-$id", 1000, false,
        "flac", null, null, null, size,
    )

    private val album = Album("al-1", "Lateralus", "Tool", "ar-1", 2001, null, "cov-al", 2, 2000, false, null, null)
    private val playlist = Playlist("pl-1", "Mix", null, "rev", 2, 2000, null, null)

    private fun workState(trackId: String): WorkInfo.State? =
        workManager.getWorkInfosForUniqueWork("offline-track:$trackId").get().firstOrNull()?.state

    private suspend fun markDownloaded(trackId: String) {
        val file = File(files.directory, "$trackId.flac").apply { writeText("audio") }
        dao.markDownloaded(trackId, file.path, file.length(), 1)
    }

    @Test
    fun `downloading an album queues each track once and prefetches artwork`() = runBlocking {
        repo.downloadAlbum(album, listOf(track("t1"), track("t2"), track("t1")))

        val collection = repo.observeCollection("album:al-1").first()!!
        assertEquals(2, collection.totalTracks)
        assertEquals(OfflineState.Queued, collection.state)
        assertEquals(WorkInfo.State.ENQUEUED, workState("t1"))
        assertEquals(WorkInfo.State.ENQUEUED, workState("t2"))
        assertTrue(prefetched.containsAll(listOf("cov-al", "cov-t1", "cov-t2")))

        markDownloaded("t1")
        val half = repo.observeCollection("album:al-1").first()!!
        assertEquals(OfflineState.Downloading, half.state)
        assertEquals(setOf("t1"), repo.observeDownloadedTrackIds().first())
    }

    @Test
    fun `a track shared with a playlist survives removing the album`() = runBlocking {
        repo.downloadAlbum(album, listOf(track("t1"), track("t2")))
        repo.downloadPlaylist(playlist, listOf(track("t2"), track("t3")))
        markDownloaded("t2")
        val shared = File(dao.get("t2")!!.localUri)

        repo.remove("album:al-1")
        assertNull(dao.get("t1"))
        assertTrue("still needed by the playlist", shared.isFile)
        assertEquals(setOf("t2"), repo.observeDownloadedTrackIds().first())

        repo.remove("playlist:pl-1")
        assertFalse(shared.isFile)
        assertNull(dao.get("t2"))
        assertEquals(emptyList<Any>(), repo.observeCollections().first())
    }

    @Test
    fun `requesting again does not re-download finished tracks, and retry revives failed ones`() = runBlocking {
        repo.downloadAlbum(album, listOf(track("t1"), track("t2")))
        markDownloaded("t1")
        dao.markFailed("t2", "Download interrupted")
        // One done, one failed: everything that could finish has, so the album reads as failed.
        assertEquals(OfflineState.Failed, repo.observeCollection("album:al-1").first()!!.state)

        repo.retry("album:al-1")
        assertEquals("Queued", dao.get("t2")?.state)
        assertEquals("Downloaded", dao.get("t1")?.state)
    }

    @Test
    fun `smart downloads never remove or downgrade what the user downloaded`() = runBlocking {
        repo.downloadAlbum(album, listOf(track("t1")))
        repo.syncAutomatic(
            listOf(
                AutomaticCollection(OfflineKind.Album, "al-1", "Lateralus", "Tool", null, listOf(track("t1"))),
                AutomaticCollection(OfflineKind.Album, "al-2", "Ænima", "Tool", null, listOf(track("t2"))),
            ),
        )
        val byId = repo.observeCollections().first().associateBy { it.id }
        assertEquals(false, byId["album:al-1"]?.automatic)
        assertEquals(true, byId["album:al-2"]?.automatic)

        repo.syncAutomatic(emptyList())
        assertEquals(listOf("album:al-1"), repo.observeCollections().first().map { it.id })
    }

    // ---- The worker ---------------------------------------------------------------------------

    private val prefetchedLyrics = mutableListOf<String>()
    private val lyricsPrefetch = object : LyricsRepository {
        override suspend fun lyrics(trackId: String) = Result.Ok(Lyrics.None)
        override suspend fun prefetch(trackId: String) {
            prefetchedLyrics += trackId
        }
    }

    /** Whether the fake home-network address answers health checks. */
    private var localUp = true

    private fun workerWith(
        server: MockWebServer,
        localServer: MockWebServer? = null,
        block: (TrackDownloadWorker) -> Unit = {},
    ): (String) -> ListenableWorker.Result {
        val url = (ServerUrl.parse(server.url("/").toString()) as Result.Ok).value
        val localUrl = localServer?.let { (ServerUrl.parse(it.url("/").toString()) as Result.Ok).value }
        val store = ServerConfigStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "server-test.preferences_pb") },
            scope,
        )
        runBlocking { store.set(ServerConfig(url, "instance", localUrl)) }
        val connection = RevConnectionManager(
            config = store.config,
            network = object : NetworkMonitor {
                override val isOnline = MutableStateFlow(true)
            },
            probe = { probed ->
                if (probed == localUrl && localUp) Result.Ok(ServerInfo("0.1.0", "instance")) else Result.Err(ProbeError.Unreachable)
            },
            scope = scope,
        )
        val http = authenticatedMediaHttpClient(
            RevHttpClient(
                MockEngine { respond("") },
                FakeSessionStore(session(access = "rev_at_dl")),
                trustedServers = connection::trustedUrls,
            ) { connection.activeUrl() },
        )
        return { trackId ->
            val worker = TestListenableWorkerBuilder<TrackDownloadWorker>(context)
                .setInputData(workDataOf(TrackDownloadWorker.KEY_TRACK_ID to trackId))
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        TrackDownloadWorker(appContext, workerParameters, dao, http, connection, files, lyricsPrefetch).also(block)
                })
                .build()
            runBlocking { worker.doWork() }
        }
    }

    @Test
    fun `an interrupted download resumes over the home network, keeping its partial file`() = runBlocking {
        val remote = MockWebServer().apply { start() }
        val home = MockWebServer().apply { start() }
        try {
            val audio = ByteArray(2_000) { (it % 251).toByte() }
            dao.upsert(OfflineTrackEntity.queued(track("t1", 2_000), 1))
            // 800 bytes arrived over the remote address before the phone got home.
            files.partFile("t1").writeBytes(audio.copyOfRange(0, 800))
            home.enqueue(
                MockResponse.Builder().code(206)
                    .body(Buffer().write(audio.copyOfRange(800, 2_000)))
                    .addHeader("Content-Range", "bytes 800-1999/2000")
                    .build(),
            )

            assertEquals(ListenableWorker.Result.success(), workerWith(remote, localServer = home)("t1"))

            val resumed = home.takeRequest()
            assertEquals("bytes=800-", resumed.headers["Range"])
            assertEquals("Bearer rev_at_dl", resumed.headers["Authorization"])
            assertEquals(0, remote.requestCount)
            assertTrue(File(dao.get("t1")!!.localUri).readBytes().contentEquals(audio))
        } finally {
            remote.close()
            home.close()
        }
    }

    @Test
    fun `away from home, downloads go to the remote address`() = runBlocking {
        val remote = MockWebServer().apply { start() }
        val home = MockWebServer().apply { start() }
        try {
            localUp = false
            dao.upsert(OfflineTrackEntity.queued(track("t1", 10), 1))
            remote.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(10))).build())

            assertEquals(ListenableWorker.Result.success(), workerWith(remote, localServer = home)("t1"))

            assertEquals("Bearer rev_at_dl", remote.takeRequest().headers["Authorization"])
            assertEquals(0, home.requestCount)
        } finally {
            remote.close()
            home.close()
        }
    }

    @Test
    fun `worker downloads a track, then resumes a partial one with a range request`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val audio = ByteArray(2_000) { (it % 251).toByte() }
            dao.upsert(OfflineTrackEntity.queued(track("t1", 2_000), 1))
            dao.upsert(OfflineTrackEntity.queued(track("t2", 2_000), 1))
            val run = workerWith(server)

            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(audio)).addHeader("Content-Type", "audio/flac").build())
            assertEquals(ListenableWorker.Result.success(), run("t1"))
            val done = dao.get("t1")!!
            assertEquals("Downloaded", done.state)
            assertTrue(File(done.localUri).readBytes().contentEquals(audio))
            val first = server.takeRequest()
            assertEquals("/api/library/tracks/t1/stream", first.url.encodedPath)
            assertEquals("Bearer rev_at_dl", first.headers["Authorization"])
            assertEquals(listOf("t1"), prefetchedLyrics)

            // Half of t2 is already on disk from an interrupted attempt.
            files.partFile("t2").writeBytes(audio.copyOfRange(0, 800))
            server.enqueue(
                MockResponse.Builder().code(206)
                    .body(Buffer().write(audio.copyOfRange(800, 2_000)))
                    .addHeader("Content-Range", "bytes 800-1999/2000")
                    .build(),
            )
            assertEquals(ListenableWorker.Result.success(), run("t2"))
            assertEquals("bytes=800-", server.takeRequest().headers["Range"])
            assertTrue(File(dao.get("t2")!!.localUri).readBytes().contentEquals(audio))
        } finally {
            server.close()
        }
    }

    @Test
    fun `worker refuses to fill the device`() = runBlocking {
        ShadowStatFs.registerStats(files.directory, 1_000, 10, 10) // ~40 KB free
        val server = MockWebServer().apply { start() }
        try {
            dao.upsert(OfflineTrackEntity.queued(track("big", 50_000_000), 1))
            assertEquals(ListenableWorker.Result.failure(), workerWith(server)("big"))
            assertEquals("Not enough storage space", dao.get("big")?.error)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `worker gives up on tracks the server no longer has`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            dao.upsert(OfflineTrackEntity.queued(track("gone"), 1))
            server.enqueue(MockResponse.Builder().code(404).build())
            assertEquals(ListenableWorker.Result.failure(), workerWith(server)("gone"))
            assertEquals("Failed", dao.get("gone")?.state)
            assertEquals("No longer in your library", dao.get("gone")?.error)
        } finally {
            server.close()
        }
    }
}
