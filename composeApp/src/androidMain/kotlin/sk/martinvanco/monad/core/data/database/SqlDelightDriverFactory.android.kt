package sk.martinvanco.monad.core.data.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import sk.martinvanco.monad.Database

actual class SqlDelightDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(
            schema = Database.Schema,
            context = context,
            name = "monad.db"
        )

        // Ensure all tables are created
        // This is safe to call multiple times because schema uses "CREATE TABLE IF NOT EXISTS"
        Database.Schema.create(driver)

        return driver
    }
}
