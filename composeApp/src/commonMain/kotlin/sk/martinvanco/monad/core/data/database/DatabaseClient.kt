package sk.martinvanco.monad.core.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import sk.martinvanco.monad.Database
import sk.martinvanco.monad.DemoRecord

class DatabaseClient(
    private val database: Database
) {
    private val queries = database.demoRecordQueries

    suspend fun getAllRecords(): List<DemoRecord> = withContext(Dispatchers.IO) {
        queries.selectAll().executeAsList()
    }

    suspend fun getRecordById(id: Long): DemoRecord? = withContext(Dispatchers.IO) {
        queries.selectById(id).executeAsOneOrNull()
    }

    suspend fun getLastRecord(): DemoRecord? = withContext(Dispatchers.IO) {
        queries.selectLast().executeAsOneOrNull()
    }

    suspend fun getRecordCount(): Long = withContext(Dispatchers.IO) {
        queries.count().executeAsOne()
    }

    suspend fun insertRecord(title: String, description: String, timestamp: Long) = withContext(Dispatchers.IO) {
        queries.insert(title, description, timestamp)
    }

    suspend fun deleteRecordById(id: Long) = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }

    suspend fun deleteLastRecord() = withContext(Dispatchers.IO) {
        queries.deleteLast()
    }

    suspend fun deleteAllRecords() = withContext(Dispatchers.IO) {
        queries.deleteAll()
    }
}
