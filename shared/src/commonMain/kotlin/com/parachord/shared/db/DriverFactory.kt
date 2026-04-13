package com.parachord.shared.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific SQLite driver factory.
 *
 * Android: AndroidSqliteDriver opens the existing Room "parachord.db" file.
 * iOS: NativeSqliteDriver creates/opens "parachord.db" in the app's documents dir.
 *
 * The schema matches Room v12 — existing Android users' databases open
 * correctly without data loss.
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}
