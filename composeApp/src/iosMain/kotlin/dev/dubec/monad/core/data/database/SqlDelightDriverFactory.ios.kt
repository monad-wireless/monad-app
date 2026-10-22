package dev.dubec.monad.core.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.dubec.monad.Database

actual class SqlDelightDriverFactory {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = Database.Schema,
            name = "monad.db"
        )
    }
}
