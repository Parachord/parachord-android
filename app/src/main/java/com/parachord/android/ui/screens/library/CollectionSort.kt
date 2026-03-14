package com.parachord.android.ui.screens.library

enum class ArtistSort(val label: String) {
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A"),
    RECENT("Recently Added"),
}

enum class AlbumSort(val label: String) {
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A"),
    ARTIST("Artist Name"),
    RECENT("Recently Added"),
}

enum class TrackSort(val label: String) {
    TITLE_ASC("Title A-Z"),
    TITLE_DESC("Title Z-A"),
    ARTIST("Artist Name"),
    ALBUM("Album Name"),
    DURATION("Duration"),
    RECENT("Recently Added"),
}

enum class FriendSort(val label: String) {
    ALPHA_ASC("A-Z"),
    ALPHA_DESC("Z-A"),
    RECENT("Recently Added"),
    ON_AIR("On Air Now"),
}
