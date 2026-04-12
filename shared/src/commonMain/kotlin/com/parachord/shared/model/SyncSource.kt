package com.parachord.shared.model

data class SyncSource(
    val itemId: String,
    val itemType: String,
    val providerId: String,
    val externalId: String? = null,
    val addedAt: Long = 0L,
    val syncedAt: Long = 0L,
)
