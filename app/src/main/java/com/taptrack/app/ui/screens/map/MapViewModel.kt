package com.taptrack.app.ui.screens.map

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.BarangayEntity
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.data.local.entity.BoundaryEntity
import com.taptrack.app.utils.BoundaryParser
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

sealed class BoundaryImportState {
    object Idle : BoundaryImportState()
    object Loading : BoundaryImportState()
    data class Success(val name: String) : BoundaryImportState()
    data class Error(val message: String) : BoundaryImportState()
}

class MapViewModel(private val c: AppContainer) : ViewModel() {

    private val projectId = c.session.projectId ?: ""

    val tapstands: StateFlow<List<TapstandEntity>> =
        (if (projectId.isNotEmpty()) c.db.tapstands().observe(projectId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val barangays: StateFlow<List<BarangayEntity>> =
        (if (projectId.isNotEmpty()) c.db.barangays().observe(projectId) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Map layers (KML / KMZ / GPX / shapefile) — kept on this phone only ─────

    val boundaries: StateFlow<List<BoundaryEntity>> = c.boundaryRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _boundaryOverlays = MutableStateFlow<List<BoundaryOverlay>>(emptyList())
    val boundaryOverlays: StateFlow<List<BoundaryOverlay>> = _boundaryOverlays.asStateFlow()

    private val _importState = MutableStateFlow<BoundaryImportState>(BoundaryImportState.Idle)
    val importState: StateFlow<BoundaryImportState> = _importState.asStateFlow()

    private val parsedCache = mutableMapOf<Long, ParsedBoundary>()

    init {
        viewModelScope.launch {
            boundaries.collect { entities ->
                withContext(Dispatchers.IO) {
                    entities.forEach { entity -> if (!parsedCache.containsKey(entity.id)) loadAndCache(entity) }
                }
                _boundaryOverlays.value = entities.filter { it.isVisible }.mapNotNull { entity ->
                    val parsed = parsedCache[entity.id] ?: return@mapNotNull null
                    BoundaryOverlay(
                        id = entity.id, name = entity.name,
                        fillColor = entity.fillColor, borderColor = entity.borderColor, showLabel = entity.showLabel,
                        polygons = parsed.polygons, polylines = parsed.polylines,
                    )
                }
            }
        }
    }

    private fun loadAndCache(entity: BoundaryEntity) {
        try {
            val file = File(entity.filePath)
            if (!file.exists()) return
            val parsed = file.inputStream().use { stream ->
                when (entity.fileType) {
                    "SHAPEFILE" -> BoundaryParser.parseShapefileZip(stream)
                    "SHP" -> BoundaryParser.parseShpFile(stream, entity.name)
                    "KMZ" -> BoundaryParser.parseKmz(stream)
                    "KML" -> BoundaryParser.parseKml(stream)
                    "GPX" -> BoundaryParser.parseGpx(stream)
                    else -> return
                }
            }
            parsedCache[entity.id] = parsed
        } catch (_: Exception) { }
    }

    fun updateBoundaryStyle(entity: BoundaryEntity, fillColor: Int, borderColor: Int, showLabel: Boolean) {
        viewModelScope.launch { c.boundaryRepository.update(entity.copy(fillColor = fillColor, borderColor = borderColor, showLabel = showLabel)) }
    }

    fun toggleVisibility(entity: BoundaryEntity) {
        viewModelScope.launch { c.boundaryRepository.update(entity.copy(isVisible = !entity.isVisible)) }
    }

    fun deleteBoundary(entity: BoundaryEntity) {
        viewModelScope.launch {
            try { File(entity.filePath).delete() } catch (_: Exception) {}
            parsedCache.remove(entity.id)
            c.boundaryRepository.delete(entity.id)
        }
    }

    fun clearImportState() { _importState.value = BoundaryImportState.Idle }

    fun importBoundary(context: Context, uri: Uri) {
        viewModelScope.launch {
            _importState.value = BoundaryImportState.Loading
            try {
                val cr = context.contentResolver
                val fileName = resolveFileName(cr, uri)
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val mime = cr.getType(uri) ?: ""
                val fileType = when {
                    ext == "kmz" || mime.contains("kmz") -> "KMZ"
                    ext == "kml" || mime.contains("kml") -> "KML"
                    ext == "gpx" || mime.contains("gpx") -> "GPX"
                    ext == "zip" || mime.contains("zip") -> "SHAPEFILE"
                    ext == "shp" -> "SHP"
                    else -> {
                        _importState.value = BoundaryImportState.Error("Unsupported file: .$ext\nSupported: .zip (shapefile), .shp, .kmz, .kml, .gpx")
                        return@launch
                    }
                }
                val parsed = withContext(Dispatchers.IO) {
                    cr.openInputStream(uri)?.use { stream ->
                        when (fileType) {
                            "KMZ" -> BoundaryParser.parseKmz(stream)
                            "KML" -> BoundaryParser.parseKml(stream)
                            "GPX" -> BoundaryParser.parseGpx(stream)
                            "SHAPEFILE" -> BoundaryParser.parseShapefileZip(stream)
                            "SHP" -> BoundaryParser.parseShpFile(stream, fileName.substringBeforeLast('.'))
                            else -> null
                        }
                    }
                }
                if (parsed == null) { _importState.value = BoundaryImportState.Error("Could not read file"); return@launch }
                if (parsed.polygons.isEmpty() && parsed.polylines.isEmpty()) {
                    _importState.value = BoundaryImportState.Error(
                        "No boundary/line geometry found.\nShapefiles: export as WGS84 (EPSG:4326).\nKML with only points: import those on the web app (Tapstands → Import KML).")
                    return@launch
                }
                val savedFile = withContext(Dispatchers.IO) { saveToInternal(context, uri, fileName) }
                val displayName = parsed.name.ifBlank { fileName.substringBeforeLast('.') }
                val borderColor = nextColor()
                val id = c.boundaryRepository.insert(
                    BoundaryEntity(name = displayName, filePath = savedFile.absolutePath, fileType = fileType,
                        borderColor = borderColor, fillColor = (borderColor and 0x00FFFFFF) or (0x30 shl 24))
                )
                parsedCache[id] = parsed
                _importState.value = BoundaryImportState.Success(displayName)
            } catch (e: Exception) {
                _importState.value = BoundaryImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    private fun resolveFileName(cr: ContentResolver, uri: Uri): String {
        var name = "layer"
        cr.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun saveToInternal(context: Context, uri: Uri, fileName: String): File {
        val dir = File(context.filesDir, "boundaries").also { it.mkdirs() }
        val dest = File(dir, "${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        return dest
    }

    private var colorIdx = 0
    private val palette = listOf(
        0xFF2196F3.toInt(), 0xFFE91E63.toInt(), 0xFF4CAF50.toInt(), 0xFFFF9800.toInt(),
        0xFF9C27B0.toInt(), 0xFFF44336.toInt(), 0xFF009688.toInt(), 0xFFFFC107.toInt(),
    )
    private fun nextColor() = palette[colorIdx++ % palette.size]

    companion object {
        fun factory(c: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MapViewModel(c) as T
        }
    }
}
