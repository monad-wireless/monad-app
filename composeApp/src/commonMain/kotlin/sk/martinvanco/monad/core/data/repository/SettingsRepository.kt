package sk.martinvanco.monad.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database

class SettingsRepository(
    private val database: Database
) {
    private val queries = database.appSettingsQueries

    companion object {
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }

    suspend fun getSetting(key: String): String? = withContext(Dispatchers.IO) {
        queries.selectByKey(key).executeAsOneOrNull()
    }

    suspend fun setSetting(key: String, value: String) = withContext(Dispatchers.IO) {
        queries.insertOrReplace(key, value)
    }

    suspend fun deleteSetting(key: String) = withContext(Dispatchers.IO) {
        queries.deleteByKey(key)
    }

    suspend fun isOnboardingCompleted(): Boolean = withContext(Dispatchers.IO) {
        getSetting(KEY_ONBOARDING_COMPLETED) == "true"
    }

    suspend fun setOnboardingCompleted(completed: Boolean) = withContext(Dispatchers.IO) {
        setSetting(KEY_ONBOARDING_COMPLETED, completed.toString())
    }
}
