package sk.martinvanco.monad.core.data.database

import app.cash.sqldelight.db.SqlDriver

expect class SqlDelightDriverFactory {
    fun createDriver(): SqlDriver
}
