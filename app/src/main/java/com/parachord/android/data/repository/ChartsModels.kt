package com.parachord.android.data.repository

data class ChartAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val rank: Int = 0,
    val genres: List<String> = emptyList(),
    val url: String? = null,
)

data class ChartSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val rank: Int = 0,
    val listeners: Long? = null,
    val playcount: Long? = null,
    val url: String? = null,
    val source: String = "",
    val mbid: String? = null,
    val spotifyId: String? = null,
)

data class ChartCountry(
    val code: String,
    val name: String,
    val lastfmName: String,
)

val CHARTS_COUNTRIES = listOf(
    ChartCountry("us", "United States", "United States"),
    ChartCountry("gb", "United Kingdom", "United Kingdom"),
    ChartCountry("ca", "Canada", "Canada"),
    ChartCountry("au", "Australia", "Australia"),
    ChartCountry("de", "Germany", "Germany"),
    ChartCountry("fr", "France", "France"),
    ChartCountry("jp", "Japan", "Japan"),
    ChartCountry("kr", "South Korea", "South Korea"),
    ChartCountry("br", "Brazil", "Brazil"),
    ChartCountry("mx", "Mexico", "Mexico"),
    ChartCountry("es", "Spain", "Spain"),
    ChartCountry("it", "Italy", "Italy"),
    ChartCountry("nl", "Netherlands", "Netherlands"),
    ChartCountry("se", "Sweden", "Sweden"),
    ChartCountry("pl", "Poland", "Poland"),
)
