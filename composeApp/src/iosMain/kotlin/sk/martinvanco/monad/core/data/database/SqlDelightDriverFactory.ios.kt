package sk.martinvanco.monad.core.data.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import sk.martinvanco.monad.Database

actual class SqlDelightDriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = Database.Schema,
            name = "monad.db"
        )

        // Ensure all tables are created
        // This is safe to call multiple times because schema uses "CREATE TABLE IF NOT EXISTS"
        Database.Schema.create(driver)

        return driver
    }
}
