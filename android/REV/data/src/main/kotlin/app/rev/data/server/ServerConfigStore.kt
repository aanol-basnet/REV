package app.rev.data.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rev.domain.model.ServerConfig
import app.rev.domain.model.ServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/** Persists which REV server this device uses. Not secret, so plain DataStore. */
class ServerConfigStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val config: Flow<ServerConfig?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val url = prefs[KEY_URL] ?: return@map null
            val instanceId = prefs[KEY_INSTANCE_ID] ?: return@map null
            val localUrl = prefs[KEY_LOCAL_URL]?.let(ServerUrl::fromNormalized)
            ServerConfig(ServerUrl.fromNormalized(url), instanceId, localUrl, prefs[KEY_LOCAL_DISCOVERY] ?: true)
        }
        .distinctUntilChanged()

    private val snapshot = config.stateIn(scope, SharingStarted.Eagerly, null)

    /** Synchronous view for hot paths (per-request checks). Null until loaded. */
    fun currentConfig(): ServerConfig? = snapshot.value

    suspend fun current(): ServerConfig? = config.first()

    suspend fun set(config: ServerConfig) {
        dataStore.edit {
            it[KEY_URL] = config.url.value
            it[KEY_INSTANCE_ID] = config.instanceId
            val local = config.localUrl
            if (local == null) it.remove(KEY_LOCAL_URL) else it[KEY_LOCAL_URL] = local.value
            it[KEY_LOCAL_DISCOVERY] = config.localDiscovery
        }
    }

    /** The user's choice: an address keeps being followed if it moves; removing one stops discovery. */
    suspend fun setLocalUrl(url: ServerUrl?) {
        dataStore.edit {
            if (url == null) it.remove(KEY_LOCAL_URL) else it[KEY_LOCAL_URL] = url.value
            it[KEY_LOCAL_DISCOVERY] = url != null
        }
    }

    suspend fun setLocalDiscovery(enabled: Boolean) {
        dataStore.edit { it[KEY_LOCAL_DISCOVERY] = enabled }
    }

    /** An address found automatically; leaves discovery on. */
    suspend fun adoptLocalUrl(url: ServerUrl) {
        dataStore.edit { it[KEY_LOCAL_URL] = url.value }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_URL)
            it.remove(KEY_INSTANCE_ID)
            it.remove(KEY_LOCAL_URL)
            it.remove(KEY_LOCAL_DISCOVERY)
        }
    }

    private companion object {
        val KEY_URL = stringPreferencesKey("server_url")
        val KEY_INSTANCE_ID = stringPreferencesKey("server_instance_id")
        val KEY_LOCAL_URL = stringPreferencesKey("server_local_url")
        val KEY_LOCAL_DISCOVERY = booleanPreferencesKey("server_local_discovery")
    }
}
