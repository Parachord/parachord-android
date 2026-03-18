package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.repository.ConcertEvent
import com.parachord.android.data.repository.ConcertsRepository
import com.parachord.android.data.repository.Resource
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConcertsViewModel @Inject constructor(
    private val concertsRepository: ConcertsRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _events = MutableStateFlow<Resource<List<ConcertEvent>>>(
        concertsRepository.cached?.let { Resource.Success(it) } ?: Resource.Loading,
    )
    val events: StateFlow<Resource<List<ConcertEvent>>> = _events.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _locationCity = MutableStateFlow<String?>(null)
    val locationCity: StateFlow<String?> = _locationCity.asStateFlow()

    private val _radiusMiles = MutableStateFlow(50)
    val radiusMiles: StateFlow<Int> = _radiusMiles.asStateFlow()

    private val _hasLocation = MutableStateFlow(false)
    val hasLocation: StateFlow<Boolean> = _hasLocation.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val loc = settingsStore.getConcertLocation()
            _locationCity.value = loc.city
            _radiusMiles.value = loc.radiusMiles
            if (loc.latitude != null && loc.longitude != null) {
                _hasLocation.value = true
                loadEvents(loc.latitude, loc.longitude, loc.radiusMiles)
            }
        }
    }

    fun setLocation(lat: Double, lon: Double, city: String) {
        viewModelScope.launch {
            val radius = _radiusMiles.value
            settingsStore.setConcertLocation(lat, lon, city, radius)
            _locationCity.value = city
            _hasLocation.value = true
            loadEvents(lat, lon, radius, forceRefresh = true)
        }
    }

    fun setRadius(radiusMiles: Int) {
        viewModelScope.launch {
            _radiusMiles.value = radiusMiles
            settingsStore.setConcertRadius(radiusMiles)
            val loc = settingsStore.getConcertLocation()
            if (loc.latitude != null && loc.longitude != null) {
                loadEvents(loc.latitude, loc.longitude, radiusMiles, forceRefresh = true)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val loc = settingsStore.getConcertLocation()
            if (loc.latitude != null && loc.longitude != null) {
                loadEvents(loc.latitude, loc.longitude, loc.radiusMiles, forceRefresh = true)
            }
        }
    }

    private fun loadEvents(
        lat: Double,
        lon: Double,
        radiusMiles: Int,
        forceRefresh: Boolean = false,
    ) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                concertsRepository.getLocalEvents(lat, lon, radiusMiles, forceRefresh).collect {
                    _events.value = it
                }
            } catch (e: Exception) {
                Log.e("ConcertsVM", "Failed to load events", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
