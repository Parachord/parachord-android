package com.parachord.android.data.db.dao

/**
 * Source-compatibility shim. The real implementation lives at
 * [com.parachord.shared.db.dao.SearchHistoryDao] in shared/commonMain. This typealias
 * preserves all existing imports across the app and tests.
 */
typealias SearchHistoryDao = com.parachord.shared.db.dao.SearchHistoryDao
