package com.parachord.android.data.repository

import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.api.ListenBrainzApi
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.db.dao.FriendDao
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Repository for managing friends and fetching their listening data.
 * Mirrors the desktop app's friend management (add, validate, fetch activity).
 */
class FriendsRepository constructor(
    private val friendDao: FriendDao,
    private val lastFmApi: LastFmApi,
    private val listenBrainzApi: ListenBrainzApi,
    private val metadataService: MetadataService,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "FriendsRepository"
    }

    /** All friends as a live-updating Flow from Room. */
    fun getAllFriends(): Flow<List<FriendEntity>> = friendDao.getAllFriends()

    /** Only friends pinned to sidebar (manual or auto-pinned). */
    fun getPinnedFriends(): Flow<List<FriendEntity>> = friendDao.getPinnedFriends()

    /** Get a single friend by ID. */
    suspend fun getFriendById(id: String): FriendEntity? = friendDao.getFriendById(id)

    /** Remove a friend locally and unfollow on the service. */
    suspend fun removeFriend(friendId: String) = withContext(Dispatchers.IO) {
        val friend = friendDao.getFriendById(friendId) ?: return@withContext
        // Add to deleted keys so sync doesn't re-add them
        val key = "${friend.service}:${friend.username.lowercase()}"
        settingsStore.addDeletedFriendKey(key)
        // Unfollow on service (ListenBrainz supports this; Last.fm does not)
        unfollowOnService(friend)
        // Delete locally
        friendDao.delete(friendId)
    }

    /** Manually pin/unpin a friend to the sidebar. */
    suspend fun pinFriend(friendId: String, pinned: Boolean) {
        friendDao.setPinned(id = friendId, pinned = pinned, auto = false)
    }

    /** Auto-pin friends that are on-air, auto-unpin those that aren't. */
    suspend fun updateAutoPins() = withContext(Dispatchers.IO) {
        val allFriends = friendDao.getAllFriendsSync()
        for (friend in allFriends) {
            if (friend.isOnAir && !friend.pinnedToSidebar) {
                // Auto-pin on-air friend
                friendDao.setPinned(id = friend.id, pinned = true, auto = true)
                Log.d(TAG, "Auto-pinned on-air friend: ${friend.displayName}")
            } else if (!friend.isOnAir && friend.autoPinned && friend.pinnedToSidebar) {
                // Auto-unpin friend that was auto-pinned but is no longer on-air
                friendDao.setPinned(id = friend.id, pinned = false, auto = false)
                Log.d(TAG, "Auto-unpinned inactive friend: ${friend.displayName}")
            }
        }
    }

    // ---------- Input Parsing (mirrors desktop parseFriendInput) ----------

    data class ParsedFriend(val service: String, val username: String)

    /**
     * Parse user input into service + username.
     * Accepts:
     * - Plain username (defaults to Last.fm)
     * - https://www.last.fm/user/{name}
     * - https://listenbrainz.org/user/{name}
     */
    fun parseFriendInput(input: String): ParsedFriend? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // Last.fm URL
        val lastFmRegex = Regex("""(?:https?://)?(?:www\.)?last\.fm/user/([^/?#]+)""", RegexOption.IGNORE_CASE)
        lastFmRegex.find(trimmed)?.let { match ->
            return ParsedFriend("lastfm", match.groupValues[1])
        }

        // ListenBrainz URL
        val lbRegex = Regex("""(?:https?://)?listenbrainz\.org/user/([^/?#]+)""", RegexOption.IGNORE_CASE)
        lbRegex.find(trimmed)?.let { match ->
            return ParsedFriend("listenbrainz", match.groupValues[1])
        }

        // Plain username — default to Last.fm
        if (trimmed.contains("/") || trimmed.contains("://")) return null
        return ParsedFriend("lastfm", trimmed)
    }

    // ---------- Add Friend ----------

    /**
     * Add a friend by raw input (username or URL).
     * Validates the user exists, fetches display info, stores in Room.
     */
    suspend fun addFriend(input: String): Resource<FriendEntity> = withContext(Dispatchers.IO) {
        val parsed = parseFriendInput(input)
            ?: return@withContext Resource.Error("Invalid username or URL")

        try {
            val friend = when (parsed.service) {
                "lastfm" -> addLastFmFriend(parsed.username)
                "listenbrainz" -> addListenBrainzFriend(parsed.username)
                else -> return@withContext Resource.Error("Unknown service")
            }

            if (friend != null) {
                // Clear deleted key if re-adding a previously removed friend
                val key = "${friend.service}:${friend.username.lowercase()}"
                settingsStore.removeDeletedFriendKey(key)
                friendDao.upsert(friend)
                // Follow on the service so desktop stays in sync
                followOnService(friend)
                // Fetch initial activity
                refreshFriendActivity(friend)
                // Re-read from DB to get latest cached data
                val updated = friendDao.getFriendById(friend.id) ?: friend
                Resource.Success(updated)
            } else {
                Resource.Error("User not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add friend: ${parsed.username}", e)
            Resource.Error("Failed to add friend: ${e.message}")
        }
    }

    private suspend fun addLastFmFriend(username: String): FriendEntity? {
        val response = lastFmApi.getUserInfo(
            user = username,
            apiKey = BuildConfig.LASTFM_API_KEY,
        )
        val user = response.user ?: return null

        return FriendEntity(
            id = "friend-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            username = user.name,
            service = "lastfm",
            displayName = user.realname?.takeIf { it.isNotBlank() } ?: user.name,
            avatarUrl = user.image.bestImageUrl(),
            addedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun addListenBrainzFriend(username: String): FriendEntity? {
        val exists = listenBrainzApi.validateUser(username)
        if (!exists) return null

        return FriendEntity(
            id = "friend-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            username = username,
            service = "listenbrainz",
            displayName = username, // ListenBrainz doesn't provide display names
            addedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Follow a user on their service so changes propagate to other Parachord clients.
     * ListenBrainz supports follow via API; Last.fm deprecated their add-friend API.
     */
    private suspend fun followOnService(friend: FriendEntity) {
        try {
            when (friend.service) {
                "listenbrainz" -> {
                    val token = settingsStore.getListenBrainzToken() ?: return
                    listenBrainzApi.followUser(friend.username, token)
                    Log.d(TAG, "Followed ${friend.username} on ListenBrainz")
                }
                // Last.fm deprecated user.addFriend — can't follow via API
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to follow ${friend.username} on ${friend.service}", e)
        }
    }

    /**
     * Unfollow a user on their service when removing them from friends.
     * ListenBrainz supports unfollow via API; Last.fm deprecated their friend API.
     */
    private suspend fun unfollowOnService(friend: FriendEntity) {
        try {
            when (friend.service) {
                "listenbrainz" -> {
                    val token = settingsStore.getListenBrainzToken() ?: return
                    listenBrainzApi.unfollowUser(friend.username, token)
                    Log.d(TAG, "Unfollowed ${friend.username} on ListenBrainz")
                }
                // Last.fm deprecated friend management API — can't unfollow via API.
                // The deleted-keys mechanism prevents re-sync instead.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unfollow ${friend.username} on ${friend.service}", e)
        }
    }

    // ---------- Sync Friends from Services ----------

    /**
     * Sync friends from Last.fm and ListenBrainz.
     * Pulls the user's friend/following lists from both services and adds
     * any users not already in the local friends DB.
     * This keeps friends in sync across desktop and mobile without a backend.
     *
     * @return number of newly synced friends
     */
    suspend fun syncFriendsFromServices(): Int = withContext(Dispatchers.IO) {
        val existingFriends = friendDao.getAllFriendsSync()
        val existingByKey = existingFriends.associate {
            "${it.service}:${it.username.lowercase()}" to it
        }
        val deletedKeys = settingsStore.getDeletedFriendKeys()
        var synced = 0

        // Sync from Last.fm
        try {
            val lastFmUsername = settingsStore.getLastFmUsername()
            if (lastFmUsername != null) {
                val response = lastFmApi.getUserFriends(
                    user = lastFmUsername,
                    apiKey = BuildConfig.LASTFM_API_KEY,
                )
                val friends = response.friends?.user ?: emptyList()
                for (user in friends) {
                    val key = "lastfm:${user.name.lowercase()}"
                    if (key !in existingByKey && key !in deletedKeys) {
                        val entity = FriendEntity(
                            id = "friend-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
                            username = user.name,
                            service = "lastfm",
                            displayName = user.realname?.takeIf { it.isNotBlank() } ?: user.name,
                            avatarUrl = user.image.bestImageUrl(),
                            addedAt = System.currentTimeMillis(),
                        )
                        friendDao.upsert(entity)
                        synced++
                        Log.d(TAG, "Synced Last.fm friend: ${user.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Last.fm friends", e)
        }

        // Sync from ListenBrainz
        try {
            val lbUsername = settingsStore.getListenBrainzUsername()
            if (lbUsername != null) {
                val following = listenBrainzApi.getUserFollowing(lbUsername)
                for (username in following) {
                    val key = "listenbrainz:${username.lowercase()}"
                    if (key !in existingByKey && key !in deletedKeys) {
                        val entity = FriendEntity(
                            id = "friend-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
                            username = username,
                            service = "listenbrainz",
                            displayName = username,
                            addedAt = System.currentTimeMillis(),
                        )
                        friendDao.upsert(entity)
                        synced++
                        Log.d(TAG, "Synced ListenBrainz following: $username")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ListenBrainz following", e)
        }

        Log.d(TAG, "Friend sync complete: $synced new friends")
        synced
    }

    // ---------- Refresh Activity ----------

    /**
     * Refresh a friend's "now playing" / most recent track.
     * Updates the cached track in Room.
     */
    suspend fun refreshFriendActivity(friend: FriendEntity) = withContext(Dispatchers.IO) {
        try {
            when (friend.service) {
                "lastfm" -> refreshLastFmActivity(friend)
                "listenbrainz" -> refreshListenBrainzActivity(friend)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh activity for ${friend.username}", e)
        }
    }

    /**
     * Refresh all friends' activity and update auto-pins.
     * Called periodically (every 2 minutes) by MainViewModel.
     */
    suspend fun refreshAllActivity() = withContext(Dispatchers.IO) {
        val allFriends = friendDao.getAllFriendsSync()
        for (friend in allFriends) {
            try {
                refreshFriendActivity(friend)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh ${friend.username}", e)
            }
        }
        updateAutoPins()
    }

    private suspend fun refreshLastFmActivity(friend: FriendEntity) {
        val response = lastFmApi.getUserRecentTracks(
            user = friend.username,
            limit = 1,
            apiKey = BuildConfig.LASTFM_API_KEY,
        )
        val track = response.recenttracks?.track?.firstOrNull()
        friendDao.updateCachedTrack(
            id = friend.id,
            name = track?.name,
            artist = track?.artist?.name,
            album = track?.album?.name,
            timestamp = track?.date?.uts?.toLongOrNull() ?: (System.currentTimeMillis() / 1000),
            artworkUrl = track?.image?.bestImageUrl(),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun refreshListenBrainzActivity(friend: FriendEntity) {
        val listens = listenBrainzApi.getRecentListens(friend.username, count = 1)
        val listen = listens.firstOrNull()
        friendDao.updateCachedTrack(
            id = friend.id,
            name = listen?.trackName,
            artist = listen?.artistName,
            album = listen?.releaseName,
            timestamp = listen?.listenedAt ?: 0,
            artworkUrl = null, // ListenBrainz doesn't provide artwork
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ---------- Friend History Data ----------

    /** Map ListenBrainz range values to Last.fm period values. */
    private fun periodToLbRange(period: String): String = when (period) {
        "7day" -> "week"
        "1month" -> "month"
        "3month" -> "quarter"
        "6month" -> "half_yearly"
        "12month" -> "year"
        "overall" -> "all_time"
        else -> "month"
    }

    fun getFriendTopTracks(username: String, service: String, period: String): Flow<Resource<List<HistoryTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val tracks = when (service) {
                "lastfm" -> {
                    val response = lastFmApi.getUserTopTracks(
                        user = username,
                        period = period,
                        limit = 50,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                    )
                    response.toptracks?.track?.mapIndexed { index, track ->
                        HistoryTrack(
                            title = track.name,
                            artist = track.artist?.name ?: "",
                            artworkUrl = track.image.bestImageUrl(),
                            playCount = track.playcount?.toIntOrNull() ?: 0,
                            rank = track.attr?.rank?.toIntOrNull() ?: (index + 1),
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val recordings = listenBrainzApi.getUserTopRecordings(
                        username = username,
                        range = periodToLbRange(period),
                        count = 50,
                    )
                    recordings.mapIndexed { index, rec ->
                        HistoryTrack(
                            title = rec.trackName,
                            artist = rec.artistName,
                            playCount = rec.listenCount,
                            rank = index + 1,
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(tracks))

            // Enrich tracks missing artwork
            val tracksNeedingArt = tracks.filter { it.artworkUrl == null }
            if (tracksNeedingArt.isNotEmpty()) {
                val enriched = enrichTrackArtwork(tracks)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend top tracks", e)
            emit(Resource.Error("Failed to load top tracks"))
        }
    }

    fun getFriendTopAlbums(username: String, service: String, period: String): Flow<Resource<List<HistoryAlbum>>> = flow {
        emit(Resource.Loading)
        try {
            val albums = when (service) {
                "lastfm" -> {
                    val response = lastFmApi.getUserTopAlbums(
                        user = username,
                        period = period,
                        limit = 50,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                    )
                    response.topalbums?.album?.mapIndexed { index, album ->
                        HistoryAlbum(
                            name = album.name,
                            artist = album.artist?.name ?: "",
                            artworkUrl = album.image.bestImageUrl(),
                            playCount = album.playcount?.toIntOrNull() ?: 0,
                            rank = album.attr?.rank?.toIntOrNull() ?: (index + 1),
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val releases = listenBrainzApi.getUserTopReleases(
                        username = username,
                        range = periodToLbRange(period),
                        count = 50,
                    )
                    releases.mapIndexed { index, rel ->
                        HistoryAlbum(
                            name = rel.releaseName,
                            artist = rel.artistName,
                            playCount = rel.listenCount,
                            rank = index + 1,
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(albums))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend top albums", e)
            emit(Resource.Error("Failed to load top albums"))
        }
    }

    fun getFriendTopArtists(username: String, service: String, period: String): Flow<Resource<List<HistoryArtist>>> = flow {
        emit(Resource.Loading)
        try {
            val artists = when (service) {
                "lastfm" -> {
                    val response = lastFmApi.getUserTopArtists(
                        user = username,
                        period = period,
                        limit = 50,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                    )
                    response.topartists?.artist?.mapIndexed { index, artist ->
                        HistoryArtist(
                            name = artist.name,
                            imageUrl = artist.image.bestImageUrl(),
                            playCount = artist.playcount?.toIntOrNull() ?: 0,
                            rank = artist.attr?.rank?.toIntOrNull() ?: (index + 1),
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val lbArtists = listenBrainzApi.getUserTopArtists(
                        username = username,
                        range = periodToLbRange(period),
                        count = 50,
                    )
                    lbArtists.mapIndexed { index, artist ->
                        HistoryArtist(
                            name = artist.name,
                            playCount = artist.listenCount,
                            rank = index + 1,
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(artists))

            // Enrich artists missing images (Last.fm deprecated images in 2020)
            val artistsNeedingImages = artists.filter { it.imageUrl == null }
            if (artistsNeedingImages.isNotEmpty()) {
                val enriched = enrichArtistImages(artists)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend top artists", e)
            emit(Resource.Error("Failed to load top artists"))
        }
    }

    fun getFriendRecentTracks(username: String, service: String): Flow<Resource<List<RecentTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val tracks = when (service) {
                "lastfm" -> {
                    val response = lastFmApi.getUserRecentTracks(
                        user = username,
                        limit = 50,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                    )
                    response.recenttracks?.track?.map { track ->
                        RecentTrack(
                            title = track.name,
                            artist = track.artist?.name ?: "",
                            album = track.album?.name,
                            artworkUrl = track.image.bestImageUrl(),
                            timestamp = track.date?.uts?.toLongOrNull() ?: 0,
                            source = "Last.fm",
                            nowPlaying = track.attr?.nowplaying == "true",
                        )
                    } ?: emptyList()
                }
                "listenbrainz" -> {
                    val listens = listenBrainzApi.getRecentListens(username, count = 50)
                    listens.map { listen ->
                        RecentTrack(
                            title = listen.trackName,
                            artist = listen.artistName,
                            album = listen.releaseName,
                            timestamp = listen.listenedAt,
                            source = "ListenBrainz",
                        )
                    }
                }
                else -> emptyList()
            }
            emit(Resource.Success(tracks))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch friend recent tracks", e)
            emit(Resource.Error("Failed to load recent tracks"))
        }
    }

    // ---------- Artwork Enrichment ----------

    private suspend fun enrichTrackArtwork(tracks: List<HistoryTrack>): List<HistoryTrack> = coroutineScope {
        val artworkCache = mutableMapOf<String, String>()
        tracks.filter { it.artworkUrl == null }.take(15).map { track ->
            async {
                try {
                    val results = metadataService.searchTracks("${track.artist} ${track.title}", limit = 1)
                    val artwork = results.firstOrNull()?.artworkUrl
                    if (artwork != null) {
                        val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
                        synchronized(artworkCache) { artworkCache[key] = artwork }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        tracks.map { track ->
            if (track.artworkUrl != null) return@map track
            val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
            val enrichedUrl = artworkCache[key]
            if (enrichedUrl != null) track.copy(artworkUrl = enrichedUrl) else track
        }
    }

    private suspend fun enrichArtistImages(artists: List<HistoryArtist>): List<HistoryArtist> = coroutineScope {
        val imageCache = mutableMapOf<String, String>()
        artists.filter { it.imageUrl == null }.take(15).map { artist ->
            async {
                try {
                    val info = metadataService.getArtistInfo(artist.name)
                    val imageUrl = info?.imageUrl
                    if (imageUrl != null) {
                        synchronized(imageCache) { imageCache[artist.name.lowercase()] = imageUrl }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        artists.map { artist ->
            if (artist.imageUrl != null) return@map artist
            val enrichedUrl = imageCache[artist.name.lowercase()]
            if (enrichedUrl != null) artist.copy(imageUrl = enrichedUrl) else artist
        }
    }
}
