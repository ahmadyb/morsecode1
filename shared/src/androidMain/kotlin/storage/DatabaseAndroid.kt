package net.morsecode.storage

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import net.morsecode.storage.db.MorseCodeDatabase

/**
 * Set once from `MainActivity.onCreate` before any repo is constructed.
 * A static holder keeps `createDriver()` parameter-free so the repos in
 * `commonMain` stay platform-agnostic.
 */
object AndroidAppContext {
    lateinit var context: Context
    val isSet: Boolean get() = ::context.isInitialized
}

actual fun createDriver(): SqlDriver =
    AndroidSqliteDriver(MorseCodeDatabase.Schema, AndroidAppContext.context, "morsecode.db")
