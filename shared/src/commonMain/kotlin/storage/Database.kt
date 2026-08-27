package net.morsecode.storage

import app.cash.sqldelight.db.SqlDriver
import net.morsecode.storage.db.MorseCodeDatabase

/**
 * Driver factory is the only platform-specific piece of persistence: Android
 * uses `AndroidSqliteDriver`, Desktop the JDBC driver. The schema and the repos
 * are identical (Section 13).
 */
expect fun createDriver(): SqlDriver

/**
 * Builds the database and applies the schema on first open.
 *
 * `MorseCodeDatabase.Schema.create` is idempotent against an existing file
 * because SQLDelight tracks migrations; on a fresh file it lays down
 * transfer_state, chat_message and trusted_device in one pass.
 */
fun createDatabase(driver: SqlDriver): MorseCodeDatabase = MorseCodeDatabase(driver)
