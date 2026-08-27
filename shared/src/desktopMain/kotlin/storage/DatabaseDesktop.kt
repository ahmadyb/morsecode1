package net.morsecode.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import net.morsecode.storage.db.MorseCodeDatabase

/**
 * Desktop database lives under the user's home dir so two Windows accounts on
 * one PC keep separate histories. The JDBC driver does not auto-create the
 * schema, so we create it on a fresh file (idempotent on an existing one).
 */
actual fun createDriver(): SqlDriver {
    val dir = File(System.getProperty("user.home"), ".morsecode").apply { mkdirs() }
    val path = File(dir, "morsecode.db").absolutePath
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
    runCatching { MorseCodeDatabase.Schema.create(driver) }
    return driver
}
