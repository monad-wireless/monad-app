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

        /** IP-157: the OS notification prompt has been shown once. Android cannot tell otherwise. */
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

        /** IP-157: `QuestCompletedScreen` has been shown once; the pre-prompt card shows only then. */
        const val KEY_QUEST_COMPLETED_SEEN = "quest_completed_seen"

        /**
         * The last `is_operator` this handset was told by `GET /api/auth/me`.
         *
         * Persisted so the first frame after launch already knows which half of the app to draw;
         * see `OperatorAccess`. A cache of a server fact, never an authorization.
         */
        const val KEY_IS_OPERATOR = "is_operator"
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

    /** Fails closed: anything other than a stored `true` reads as a plain participant. */
    suspend fun isOperator(): Boolean = withContext(Dispatchers.IO) {
        getSetting(KEY_IS_OPERATOR) == "true"
    }

    suspend fun setOperator(isOperator: Boolean) = withContext(Dispatchers.IO) {
        setSetting(KEY_IS_OPERATOR, isOperator.toString())
    }
}
