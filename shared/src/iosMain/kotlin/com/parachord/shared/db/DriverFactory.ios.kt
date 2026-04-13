package com.parachord.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * iOS SQLite driver — creates/opens "parachord.db" in the app container.
 * New iOS installations start with the v12-equivalent schema directly.
 */
actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(
            schema = ParachordDb.Schema,
            name = "parachord.db",
        )
}
