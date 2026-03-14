package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.CHARTS_COUNTRIES
import com.parachord.android.data.repository.ChartAlbum
import com.parachord.android.data.repository.ChartSong
import com.parachord.android.data.repository.ChartsRepository
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PopOfTheTopsViewModel @Inject constructor(
    private val chartsRepository: ChartsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
) : ViewModel() {

    companion object {
        private const val TAG = "PopOfTheTopsVM"
    }

    private val _albums = MutableStateFlow<List<ChartAlbum>>(emptyList())
    val albums: StateFlow<List<ChartAlbum>> = _albums

    private val _songs = MutableStateFlow<List<ChartSong>>(emptyList())
    val songs: StateFlow<List<ChartSong>> = _songs

    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading: StateFlow<Boolean> = _albumsLoading

    private val _songsLoading = MutableStateFlow(false)
    val songsLoading: StateFlow<Boolean> = _songsLoading

    private val _selectedCountry = MutableStateFlow("us")
    val selectedCountry: StateFlow<String> = _selectedCountry

    private val _songsSource = MutableStateFlow("apple")
    val songsSource: StateFlow<String> = _songsSource

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val countries = CHARTS_COUNTRIES

    init {
        loadAlbums()
        loadSongs()
    }

    fun setCountry(code: String) {
        _selectedCountry.value = code
        loadAlbums()
        loadSongs()
    }

    fun setSongsSource(source: String) {
        _songsSource.value = source
        loadSongs()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun filterAlbums(albums: List<ChartAlbum>): List<ChartAlbum> {
        val q = _searchQuery.value.lowercase().trim()
        if (q.isBlank()) return albums
        return albums.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }

    fun filterSongs(songs: List<ChartSong>): List<ChartSong> {
        val q = _searchQuery.value.lowercase().trim()
        if (q.isBlank()) return songs
        return songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }

    fun playSong(song: ChartSong, allSongs: List<ChartSong>) {
        viewModelScope.launch {
            try {
                // Resolve all songs concurrently
                val entities = allSongs.mapNotNull { s -> resolveSong(s) }
                if (entities.isEmpty()) return@launch
                // Find the tapped song's index in the resolved list
                val resolvedIndex = entities.indexOfFirst { it.title == song.title && it.artist == song.artist }
                    .coerceAtLeast(0)
                playbackController.playQueue(entities, resolvedIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play '${song.title}'", e)
            }
        }
    }

    private suspend fun resolveSong(song: ChartSong): TrackEntity? {
        return try {
            val query = "${song.artist} - ${song.title}"
            val sources = resolverManager.resolveWithHints(
                query = query,
                spotifyId = song.spotifyId,
            )
            val best = resolverScoring.selectBest(sources) ?: return null
            TrackEntity(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                artworkUrl = song.artworkUrl,
                sourceType = best.sourceType,
                sourceUrl = best.url,
                resolver = best.resolver,
                spotifyUri = best.spotifyUri,
                soundcloudId = best.soundcloudId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve '${song.title}'", e)
            null
        }
    }

    private fun loadAlbums() {
        viewModelScope.launch {
            _albumsLoading.value = true
            _albums.value = chartsRepository.getAppleMusicAlbums(_selectedCountry.value)
            _albumsLoading.value = false
        }
    }

    private fun loadSongs() {
        viewModelScope.launch {
            _songsLoading.value = true
            _songs.value = when (_songsSource.value) {
                "lastfm" -> chartsRepository.getLastfmCharts(_selectedCountry.value)
                else -> chartsRepository.getAppleMusicSongs(_selectedCountry.value)
            }
            _songsLoading.value = false
        }
    }
}
