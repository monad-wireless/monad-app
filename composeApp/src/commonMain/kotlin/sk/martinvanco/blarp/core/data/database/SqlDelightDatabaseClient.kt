package sk.martinvanco.blarp.core.data.database

import app.cash.sqldelight.db.SqlDriver

object SqlDelightDatabaseClient {

    private var driver: SqlDriver? = null

    fun initialize(driverFactory: SqlDelightDriverFactory) {
        if (driver == null) {
            driver = driverFactory.createDriver()
        }
    }

    fun getDriver(): SqlDriver {
        return driver ?: throw IllegalStateException(
            "SqlDelightDatabaseClient must be initialized before use"
        )
    }
}
