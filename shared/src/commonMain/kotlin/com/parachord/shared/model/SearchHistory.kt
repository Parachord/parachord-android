package com.parachord.shared.model

data class SearchHistory(
    val id: Long = 0,
    val query: String,
    val resultType: String? = null,
    val resultName: String? = null,
    val resultArtist: String? = null,
    val artworkUrl: String? = null,
    val timestamp: Long = 0L,
)
