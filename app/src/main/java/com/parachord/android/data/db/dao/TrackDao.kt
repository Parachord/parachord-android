package com.parachord.android.data.db.dao

/**
 * Source-compatibility shim. The real implementation lives at
 * [com.parachord.shared.db.dao.TrackDao] in shared/commonMain. This typealias
 * preserves all existing imports across the app and tests.
 */
typealias TrackDao = com.parachord.shared.db.dao.TrackDao
