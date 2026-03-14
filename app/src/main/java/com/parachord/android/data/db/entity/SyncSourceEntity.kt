package com.parachord.android.data.db.entity

import androidx.room.Entity

@Entity(
    tableName = "sync_sources",
    primaryKeys = ["itemId", "itemType", "providerId"],
)
data class SyncSourceEntity(
    val itemId: String,
    val itemType: String,
    val providerId: String,
    val externalId: String? = null,
    val addedAt: Long = 0L,
    val syncedAt: Long = System.currentTimeMillis(),
)
