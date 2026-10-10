package app.rev.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rev.domain.repository.AppTheme
import app.rev.domain.repository.AppearanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppearanceStore(private val dataStore: DataStore<Preferences>) : AppearanceRepository {
    override val theme: Flow<AppTheme> = dataStore.data.map { prefs ->
        AppTheme.entries.firstOrNull { it.name == prefs[THEME] } ?: AppTheme.Dark
    }

    override suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { it[THEME] = theme.name }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
    }
}
