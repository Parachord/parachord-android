package com.parachord.android.data.db.dao

/**
 * Source-compatibility shim. The real implementation lives at
 * [com.parachord.shared.db.dao.SyncSourceDao] in shared/commonMain. This typealias
 * preserves all existing imports across the app and tests.
 */
typealias SyncSourceDao = com.parachord.shared.db.dao.SyncSourceDao
