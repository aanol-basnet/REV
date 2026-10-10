package app.rev.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rev.data.remote.RevJson
import app.rev.domain.modes.ActiveMode
import app.rev.domain.modes.BuiltInModes
import app.rev.domain.modes.ListeningMode
import app.rev.domain.modes.ModeLean
import app.rev.domain.modes.ModeRepository
import app.rev.domain.modes.ModeSource
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Your own listening modes and the one in use, in the app's settings. */
class ModeStore(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModeRepository {
    override val modes: Flow<List<ListeningMode>> = dataStore.data.map { BuiltInModes.all + custom(it) }.distinctUntilChanged()

    override val active: Flow<ActiveMode?> = dataStore.data.map { prefs ->
        val saved = prefs[ACTIVE]?.let { runCatching { RevJson.decodeFromString(ActiveDto.serializer(), it) }.getOrNull() }
            ?: return@map null
        val mode = (BuiltInModes.all + custom(prefs)).firstOrNull { it.id == saved.id } ?: return@map null
        ActiveMode(mode, ModeSource.entries.firstOrNull { it.wire == saved.source } ?: ModeSource.Picked, saved.since)
    }.distinctUntilChanged()

    override suspend fun pick(modeId: String?) = save(modeId, ModeSource.Picked)

    override suspend fun setAuto(mode: ListeningMode?) = save(mode?.id, ModeSource.Auto)

    override suspend fun addCustom(name: String, lean: ModeLean): ListeningMode {
        val mode = ListeningMode(ListeningMode.CUSTOM_PREFIX + UUID.randomUUID().toString().take(8), name.trim().take(MAX_NAME), lean)
        dataStore.edit { prefs -> prefs[CUSTOM] = encode(custom(prefs) + mode) }
        return mode
    }

    override suspend fun removeCustom(id: String) {
        dataStore.edit { prefs ->
            prefs[CUSTOM] = encode(custom(prefs).filterNot { it.id == id })
            if (prefs[ACTIVE]?.contains("\"$id\"") == true) prefs.remove(ACTIVE)
        }
    }

    private suspend fun save(modeId: String?, source: ModeSource) {
        if (modeId == null) {
            dataStore.edit { it.remove(ACTIVE) }
            return
        }
        // Picking the mode already picked keeps when it started.
        val current = active.first()
        if (current?.mode?.id == modeId && current.source == source) return
        dataStore.edit { it[ACTIVE] = RevJson.encodeToString(ActiveDto.serializer(), ActiveDto(modeId, source.wire, clock())) }
    }

    private fun custom(prefs: Preferences): List<ListeningMode> = prefs[CUSTOM]?.let { json ->
        runCatching { RevJson.decodeFromString(ListSerializer(CustomDto.serializer()), json) }.getOrNull()
    }.orEmpty().map { ListeningMode(it.id, it.name, ModeLean.entries.firstOrNull { l -> l.wire == it.lean } ?: ModeLean.None) }

    private fun encode(modes: List<ListeningMode>) =
        RevJson.encodeToString(ListSerializer(CustomDto.serializer()), modes.map { CustomDto(it.id, it.name, it.lean.wire) })

    @Serializable
    private data class CustomDto(val id: String, val name: String, val lean: String)

    @Serializable
    private data class ActiveDto(val id: String, val source: String, val since: Long)

    private companion object {
        val CUSTOM = stringPreferencesKey("custom_modes")
        val ACTIVE = stringPreferencesKey("active_mode")
        const val MAX_NAME = 40
    }
}
