package com.taptrack.app.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.BarangayEntity
import com.taptrack.app.data.local.MapLayerEntity
import com.taptrack.app.data.local.visible
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.utils.GeoJsonLayer
import com.taptrack.app.utils.ParsedBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MapViewModel(private val c: AppContainer) : ViewModel() {

    private val projectId = c.session.projectId ?: ""

    val tapstands: StateFlow<List<TapstandEntity>> =
        (if (projectId.isNotEmpty()) c.db.tapstands().observe(projectId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val barangays: StateFlow<List<BarangayEntity>> =
        (if (projectId.isNotEmpty()) c.db.barangays().observe(projectId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val applicants: StateFlow<List<com.taptrack.app.data.local.ApplicantEntity>> =
        (if (projectId.isNotEmpty()) c.db.applicants().observe(projectId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Shared map layers (managed by the office on the web; downloaded on sync) ──

    val layers: StateFlow<List<MapLayerEntity>> =
        (if (projectId.isNotEmpty()) c.db.mapLayers().observe(projectId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _overlays = MutableStateFlow<List<BoundaryOverlay>>(emptyList())
    val overlays: StateFlow<List<BoundaryOverlay>> = _overlays.asStateFlow()

    private val parsed = mutableMapOf<String, ParsedBoundary>()   // key = id|localVersion

    init {
        viewModelScope.launch {
            layers.collect { list ->
                val shown = list.filter { it.visible && it.localFile != null }
                withContext(Dispatchers.IO) {
                    for (l in shown) {
                        val key = "${l.id}|${l.localVersion}"
                        if (key !in parsed) runCatching { GeoJsonLayer.read(File(l.localFile!!), l.name) }.onSuccess { parsed[key] = it }
                    }
                }
                _overlays.value = shown.mapNotNull { l ->
                    val p = parsed["${l.id}|${l.localVersion}"] ?: return@mapNotNull null
                    BoundaryOverlay(
                        id = l.id.hashCode().toLong(), name = l.name,
                        fillColor = l.fillColor, borderColor = l.strokeColor, showLabel = l.showLabel,
                        polygons = p.polygons, polylines = p.polylines,
                    )
                }
            }
        }
    }

    /** On/off on this phone only. */
    fun toggleLayer(l: MapLayerEntity) {
        viewModelScope.launch { c.db.mapLayers().setUserVisible(l.id, !l.visible) }
    }

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MapViewModel(c) as T
        }
    }
}
