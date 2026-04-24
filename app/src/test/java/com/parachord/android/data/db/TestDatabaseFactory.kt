package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb

/**
 * Creates a fresh in-memory SQLDelight database for each test. Mirrors the
 * CREATE TABLE statements that [AndroidModule] runs at DB bind time —
 * SQLDelight's auto-generated schema is sufficient for the core tables,
 * but any idempotent ALTER TABLE migrations must also run here so tests
 * see the same schema existing installs get.
 */
object TestDatabaseFactory {
    fun create(): ParachordDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ParachordDb.Schema.create(driver)
        return ParachordDb(driver)
    }
}
