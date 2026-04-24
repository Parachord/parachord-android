package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb

/**
 * Fresh in-memory SQLDelight DB per call, built from [ParachordDb.Schema].
 * Migration backfills in [AndroidModule] are intentionally not replayed —
 * they exist only for installs upgraded from the original Room v12 schema
 * and add nothing beyond what `.sq` files already declare.
 */
object TestDatabaseFactory {
    fun create(): ParachordDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ParachordDb.Schema.create(driver)
        return ParachordDb(driver)
    }
}
