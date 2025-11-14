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
                token = result.token
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
}
