package sk.martinvanco.monad.auth.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.User

class UserRepository(
    private val database: Database
) {
    private val queries = database.userQueries

    suspend fun getAllUsers(): List<User> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList()
    }

    suspend fun getUserById(id: Long): User? = withContext(Dispatchers.IO) {
        queries.selectById(id).executeAsOneOrNull()
    }

    suspend fun getUserByEmail(email: String): User? = withContext(Dispatchers.IO) {
        queries.selectByEmail(email).executeAsOneOrNull()
    }

    suspend fun getCurrentUser(): User? = withContext(Dispatchers.IO) {
        queries.getCurrentUser().executeAsOneOrNull()?.let { result ->
            User(
                id = result.id,
                backendId = result.backendId,
                email = result.email,
                name = result.name,
                token = result.token,
                activeQuestId = result.activeQuestId,
                activeEnrollmentId = result.activeEnrollmentId
            )
        }
    }

    suspend fun insertUser(
        backendId: String?,
        email: String,
        name: String?,
        token: String?
    ) = withContext(Dispatchers.IO) {
        queries.insert(backendId, email, name, token)
    }

    suspend fun updateToken(id: Long, token: String?) = withContext(Dispatchers.IO) {
        queries.updateToken(token, id)
    }

    suspend fun updateUser(id: Long, backendId: String?, email: String, name: String?) = withContext(Dispatchers.IO) {
        queries.updateUser(backendId, email, name, id)
    }

    suspend fun deleteUserById(id: Long) = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }

    suspend fun deleteAllUsers() = withContext(Dispatchers.IO) {
        queries.deleteAll()
    }

    suspend fun clearToken(id: Long) = withContext(Dispatchers.IO) {
        queries.clearToken(id)
    }

    suspend fun setActiveQuestId(userId: Long, questId: String, enrollmentId: String) = withContext(Dispatchers.IO) {
        queries.setActiveQuestId(questId, enrollmentId, userId)
    }

    suspend fun clearActiveQuestId(userId: Long) = withContext(Dispatchers.IO) {
        queries.clearActiveQuestId(userId)
    }

    suspend fun getActiveQuestId(userId: Long): String? = withContext(Dispatchers.IO) {
        queries.getActiveQuestId(userId).executeAsOneOrNull()?.activeQuestId
    }

    suspend fun getActiveEnrollmentId(userId: Long): String? = withContext(Dispatchers.IO) {
        queries.getActiveEnrollmentId(userId).executeAsOneOrNull()?.activeEnrollmentId
    }

    suspend fun getCurrentUserActiveQuestId(): String? = withContext(Dispatchers.IO) {
        getCurrentUser()?.let { user ->
            getActiveQuestId(user.id)
        }
    }

    suspend fun getCurrentUserActiveEnrollmentId(): String? = withContext(Dispatchers.IO) {
        getCurrentUser()?.let { user ->
            getActiveEnrollmentId(user.id)
        }
    }
}
