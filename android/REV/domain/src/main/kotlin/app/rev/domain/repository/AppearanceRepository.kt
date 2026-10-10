package app.rev.domain.repository

import kotlinx.coroutines.flow.Flow

enum class AppTheme { Dark, Amoled }

interface AppearanceRepository {
    val theme: Flow<AppTheme>
    suspend fun setTheme(theme: AppTheme)
}
