package app.rev.data.stats

import app.rev.data.local.stats.LearnedExampleDao
import app.rev.data.local.stats.LearnedExampleEntity
import app.rev.data.remote.RevJson
import app.rev.data.remote.SkipModelDto
import app.rev.domain.learning.FeatureInput
import app.rev.domain.learning.OnlineSkipModel
import app.rev.domain.learning.PersonalModel
import app.rev.domain.learning.SkipFeatures
import app.rev.domain.learning.SkipModel
import app.rev.domain.model.Result
import app.rev.domain.repository.LibraryError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The personal skip model on the phone. Starts from the server's latest model (cached in a file,
 * so it works offline) and learns from every listen and feedback button as it happens. Each
 * example is also kept (`learned_examples`), so when a newer model comes from the server, the
 * phone learns again whatever that model hasn't seen: listens made offline, or not yet synced.
 */
class PersonalModelImpl internal constructor(
    /** The server's latest model; null when it hasn't trained one. */
    private val fetch: suspend () -> Result<SkipModelDto?, LibraryError>,
    private val examples: LearnedExampleDao,
    private val cacheFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : PersonalModel {
    private val lock = Mutex()
    private var base: SkipModel? = null
    private var model: OnlineSkipModel? = null
    private var checkedAt = 0L

    override suspend fun skipChance(input: FeatureInput): Double? = lock.withLock {
        val current = current()
        if (current.examples == 0) null else current.skipChance(SkipFeatures.of(input))
    }

    override suspend fun confidence(): Double = lock.withLock {
        (current().examples / TRUSTED_EXAMPLES.toDouble()).coerceAtMost(1.0)
    }

    override suspend fun learn(input: FeatureInput, skipped: Boolean, weight: Double, startedAt: Long) = lock.withLock {
        val features = SkipFeatures.of(input)
        current().learn(features, skipped, weight)
        examples.insert(LearnedExampleEntity(startedAt = startedAt, features = features.joinToString("\n"), skipped = skipped, weight = weight))
        if (examples.count() > MAX_KEPT) examples.trim(MAX_KEPT)
    }

    /** Fetches the server's newest model now and then; a new one is caught up with this phone's newer listens. */
    override suspend fun refresh() = lock.withLock {
        val now = clock()
        if (now - checkedAt < CHECK_EVERY_MS) return@withLock
        checkedAt = now
        val fetched = (fetch() as? Result.Ok)?.value ?: return@withLock
        if (fetched.trainedAt == base?.trainedAt) return@withLock
        writeCache(fetched)
        rebuild(fetched.toDomain())
    }

    /** Forgets the model and what it learned (signed out, or another server). */
    suspend fun clear() = lock.withLock {
        base = null
        model = OnlineSkipModel(null)
        checkedAt = 0
        examples.clear()
        withContext(Dispatchers.IO) { cacheFile.delete() }
    }

    private suspend fun current(): OnlineSkipModel {
        model?.let { return it }
        rebuild(readCache()?.toDomain())
        return model!!
    }

    private suspend fun rebuild(newBase: SkipModel?) {
        base = newBase
        val online = OnlineSkipModel(newBase)
        for (example in examples.since(newBase?.dataUntil ?: 0)) {
            online.learn(example.features.split("\n"), example.skipped, example.weight)
        }
        model = online
    }

    private suspend fun readCache(): SkipModelDto? = withContext(Dispatchers.IO) {
        runCatching { RevJson.decodeFromString(SkipModelDto.serializer(), cacheFile.readText()) }.getOrNull()
    }

    private suspend fun writeCache(dto: SkipModelDto) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(RevJson.encodeToString(SkipModelDto.serializer(), dto))
            tmp.renameTo(cacheFile)
        }
    }

    private companion object {
        /** The model gets its full say in the smart queue after this many listens. */
        const val TRUSTED_EXAMPLES = 1000
        const val MAX_KEPT = 20_000
        const val CHECK_EVERY_MS = 3 * 3_600_000L
    }
}
