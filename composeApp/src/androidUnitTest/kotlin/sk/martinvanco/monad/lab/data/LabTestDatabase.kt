package sk.martinvanco.monad.lab.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import sk.martinvanco.monad.Database

/**
 * A real schema, in memory, per test.
 *
 * The two things being tested here — the continuity-epoch guard in [LabSessionRecovery] and the
 * upload-then-delete rule in [LabSessionUploader] — are both claims about **what SQL does to rows**.
 * Faking the repository would test the fake. An in-memory JDBC driver runs the same `.sq` files and
 * the same migrations the handset does, on the JVM, in milliseconds.
 */
internal fun inMemoryDatabase(): Pair<Database, JdbcSqliteDriver> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    Database.Schema.create(driver)
    return Database(driver) to driver
}
