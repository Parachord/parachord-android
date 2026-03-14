package com.parachord.android.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.repository.FreshDrop
import com.parachord.android.data.repository.FreshDropsRepository
import com.parachord.android.data.repository.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Fresh Drops screen.
 *
 * Matches the desktop's new-releases state management:
 * - Loading state, releases list, filter by type, and search
 * - Filter options: all (default), album, ep, single
 * - Search by release title or artist name
 */
@HiltViewModel
class FreshDropsViewModel @Inject constructor(
    private val repository: FreshDropsRepository,
) : ViewModel() {

    private val _releases = MutableStateFlow<Resource<List<FreshDrop>>>(Resource.Loading)
    val releases: StateFlow<Resource<List<FreshDrop>>> = _releases

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _filterType = MutableStateFlow("all")
    val filterType: StateFlow<String> = _filterType

    init {
        loadReleases()
    }

    fun refresh() {
        loadReleases(forceRefresh = true)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilterType(type: String) {
        _filterType.value = type
    }

    /**
     * Get filtered releases based on current filter type and search query.
     * Called from the composable as a derived computation.
     */
    fun filterReleases(releases: List<FreshDrop>): List<FreshDrop> {
        val query = _searchQuery.value.lowercase().trim()
        val type = _filterType.value

        val filtered = releases.filter { release ->
            // Type filter
            val matchesType = when (type) {
                "album" -> release.releaseType == "album"
                "ep" -> release.releaseType == "ep"
                "single" -> release.releaseType == "single"
                else -> true // "all"
            }
            // Search filter
            val matchesSearch = query.isBlank() ||
                release.title.lowercase().contains(query) ||
                release.artist.lowercase().contains(query)

            matchesType && matchesSearch
        }

        return filtered
    }

    private fun loadReleases(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            repository.getFreshDrops(forceRefresh).collect {
                _releases.value = it
            }
        }
    }
}
