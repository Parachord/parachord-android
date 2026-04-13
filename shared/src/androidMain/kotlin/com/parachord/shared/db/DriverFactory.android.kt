package com.parachord.shared.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android SQLite driver — opens the existing Room "parachord.db" file.
 *
 * The schema matches Room v12. Existing users' databases are opened
 * in-place without data migration — same tables, same columns, same file.
 */
actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = ParachordDb.Schema,
            context = context,
            name = "parachord.db",
        )
}
