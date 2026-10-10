package app.rev.data.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.rev.domain.model.SmartDownloadSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class OfflineSettings(private val dataStore: DataStore<Preferences>) {
    /** Download only on unmetered networks. On by default: albums are large. */
    val wifiOnly: Flow<Boolean> = dataStore.data.map { it[WIFI_ONLY] ?: true }

    suspend fun wifiOnlyNow(): Boolean = wifiOnly.first()

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = enabled }
    }

    val smart: Flow<SmartDownloadSettings> = dataStore.data.map {
        SmartDownloadSettings(
            favorites = it[SMART_FAVORITES] ?: false,
            recentAlbums = it[SMART_RECENT] ?: 0,
            limitBytes = it[SMART_LIMIT] ?: SmartDownloadSettings().limitBytes,
        )
    }

    suspend fun setSmart(settings: SmartDownloadSettings) {
        dataStore.edit {
            it[SMART_FAVORITES] = settings.favorites
            it[SMART_RECENT] = settings.recentAlbums
            it[SMART_LIMIT] = settings.limitBytes
        }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("offline_wifi_only")
        val SMART_FAVORITES = booleanPreferencesKey("smart_favorites")
        val SMART_RECENT = intPreferencesKey("smart_recent_albums")
        val SMART_LIMIT = longPreferencesKey("smart_limit_bytes")
    }
}
