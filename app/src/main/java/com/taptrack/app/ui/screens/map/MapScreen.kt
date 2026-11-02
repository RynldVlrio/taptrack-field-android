package com.taptrack.app.ui.screens.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.data.local.entity.BoundaryEntity
import com.taptrack.app.ui.TsKind
import com.taptrack.app.ui.TsStatus
import com.taptrack.app.ui.components.BoundaryColorEditDialog
import com.taptrack.app.ui.components.BoundaryManagerSheet
import com.taptrack.app.ui.components.MapDownloadDialog
import com.taptrack.app.ui.components.MapPin
import com.taptrack.app.ui.components.MapSearchBar
import com.taptrack.app.ui.components.OsmMapView
import com.taptrack.app.ui.kind
import com.taptrack.app.ui.label
import com.taptrack.app.utils.RouteResult
import com.taptrack.app.utils.fetchRoute
import com.taptrack.app.utils.formatCoordinates
import com.taptrack.app.utils.formatDistance
import com.taptrack.app.utils.formatDuration
import com.taptrack.app.utils.getLastKnownLocation
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    container: AppContainer,
    onOpenTapstand: (String) -> Unit,
    onAddTapstand: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val vm: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val tapstands by vm.tapstands.collectAsStateWithLifecycle()
    val barangays by vm.barangays.collectAsStateWithLifecycle()
    val boundaries by vm.boundaries.collectAsStateWithLifecycle()
    val boundaryOverlays by vm.boundaryOverlays.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val canEdit = container.session.canEdit

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    LaunchedEffect(Unit) { if (!locationPermissions.allPermissionsGranted) locationPermissions.launchMultiplePermissionRequest() }

    val prefs = remember { context.getSharedPreferences("tt_map", Context.MODE_PRIVATE) }
    var satellite by remember { mutableStateOf(prefs.getBoolean("satellite", false)) }
    var showProposed by remember { mutableStateOf(true) }
    var locateTrigger by remember { mutableIntStateOf(0) }
    var searchTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var searchPin by remember { mutableStateOf<GeoPoint?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }
    var editingBoundary by remember { mutableStateOf<BoundaryEntity?>(null) }
    var route by remember { mutableStateOf<RouteResult?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TapstandEntity?>(null) }
    var pressPoint by remember { mutableStateOf<GeoPoint?>(null) }
    val scope = rememberCoroutineScope()

    // Compass heading
    var azimuth by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val ori = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                azimuth = Math.toDegrees(ori[0].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importBoundary(context, it) }
    }
    LaunchedEffect(importState) {
        when (val s = importState) {
            is BoundaryImportState.Success -> { Toast.makeText(context, "Layer \"${s.name}\" added", Toast.LENGTH_SHORT).show(); vm.clearImportState() }
            is BoundaryImportState.Error -> { Toast.makeText(context, s.message, Toast.LENGTH_LONG).show(); vm.clearImportState() }
            else -> {}
        }
    }
    LaunchedEffect(locationPermissions.allPermissionsGranted) { if (locationPermissions.allPermissionsGranted) locateTrigger++ }

    val pins = remember(tapstands, showProposed) {
        tapstands.filter { it.kind == TsKind.OFFICIAL || (showProposed && it.kind == TsKind.PROPOSED) }
            .map { MapPin(it.id, it.lat, it.lng, TsStatus.argb(it.status), it.label) }
    }

    fun routeTo(lat: Double, lng: Double) {
        scope.launch {
            routeLoading = true
            val loc = context.getLastKnownLocation()
            if (loc == null) {
                Toast.makeText(context, "Waiting for GPS location…", Toast.LENGTH_SHORT).show()
            } else {
                route = fetchRoute(loc.latitude, loc.longitude, lat, lng, context.packageName)
                if (route == null) Toast.makeText(context, "No route found (needs internet)", Toast.LENGTH_SHORT).show()
            }
            routeLoading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        OsmMapView(
            pins = pins,
            satellite = satellite,
            locateTrigger = locateTrigger,
            searchTarget = searchTarget,
            routePoints = route?.points,
            boundaryOverlays = boundaryOverlays,
            searchPin = searchPin,
            modifier = Modifier.fillMaxSize(),
            onPinClick = { id -> selected = tapstands.firstOrNull { it.id == id } },
            onSearchPinClick = { pressPoint = searchPin },
            onMapViewReady = { mapViewRef = it },
            onLongPress = { lat, lng, _ -> searchPin = null; pressPoint = GeoPoint(lat, lng) },
        )

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            route?.let { r ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Navigation, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${formatDistance(r.distanceMeters)} · ${formatDuration(r.durationSeconds)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { route = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Clear route", Modifier.size(16.dp)) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MapCompass(azimuth, onResetNorth = { mapViewRef?.let { it.mapOrientation = 0f; it.invalidate() } })
                Spacer(Modifier.width(8.dp))
                MapSearchBar(
                    onResultSelected = { point, _ -> searchTarget = point; searchPin = point; pressPoint = point },
                    modifier = Modifier.weight(1f),
                )
            }
            if (!locationPermissions.allPermissionsGranted) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                    Text("Location permission is needed for GPS", Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (routeLoading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))

        if (showLegend) {
            Card(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Legend", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    TsStatus.all.filter { it.key != "cancelled" }.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(s.color)))
                            Spacer(Modifier.width(6.dp))
                            Text(s.label + if (s.key == "proposed") " (to verify)" else "", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(onClick = { showLegend = !showLegend }) { Icon(Icons.Default.Info, "Legend") }
            SmallFloatingActionButton(onClick = { showProposed = !showProposed },
                containerColor = if (showProposed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface) {
                Icon(Icons.Default.Flag, if (showProposed) "Hide proposed" else "Show proposed")
            }
            SmallFloatingActionButton(onClick = { satellite = !satellite; prefs.edit().putBoolean("satellite", satellite).apply() },
                containerColor = if (satellite) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface) {
                Icon(Icons.Default.Satellite, "Satellite")
            }
            SmallFloatingActionButton(onClick = { showDownload = true }) { Icon(Icons.Default.Download, "Download map area") }
            SmallFloatingActionButton(onClick = { showLayers = true },
                containerColor = if (boundaries.isNotEmpty()) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface) {
                Icon(Icons.Default.Layers, "Map layers")
            }
            FloatingActionButton(onClick = { locateTrigger++ }) { Icon(Icons.Default.MyLocation, "My location") }
        }
    }

    // ── Tapstand pin sheet ──────────────────────────────────────────────────────
    selected?.let { t ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(TsStatus.color(t.status)))
                    Spacer(Modifier.width(8.dp))
                    Text(t.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(TsStatus.of(t.status).label + (barangays.firstOrNull { it.id == t.barangayId }?.let { " · ${it.name}" } ?: "") +
                    (t.sitio?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(formatCoordinates(t.lat, t.lng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selected = null; onOpenTapstand(t.id) }) {
                        Text(if (t.kind == TsKind.PROPOSED && canEdit) "Open / verify" else "Open")
                    }
                    OutlinedButton(onClick = { selected = null; routeTo(t.lat, t.lng) }) { Text("Route") }
                    OutlinedButton(onClick = { openDirections(context, t.lat, t.lng) }) { Text("Directions") }
                }
            }
        }
    }

    // ── Long-press / search sheet ────────────────────────────────────────────────
    pressPoint?.let { p ->
        ModalBottomSheet(onDismissRequest = { pressPoint = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selected point", style = MaterialTheme.typography.titleMedium)
                Text(formatCoordinates(p.latitude, p.longitude), style = MaterialTheme.typography.bodySmall)
                if (canEdit) {
                    Button(onClick = { pressPoint = null; searchPin = null; onAddTapstand(p.latitude, p.longitude) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add tapstand here")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("coordinates", formatCoordinates(p.latitude, p.longitude)))
                        Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                    OutlinedButton(onClick = { pressPoint = null; routeTo(p.latitude, p.longitude) }) { Text("Route") }
                    OutlinedButton(onClick = { openDirections(context, p.latitude, p.longitude) }) { Text("Directions") }
                }
            }
        }
    }

    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }) {
            BoundaryManagerSheet(
                boundaries = boundaries,
                onToggleVisibility = { vm.toggleVisibility(it) },
                onDelete = { vm.deleteBoundary(it) },
                onEditStyle = { b -> editingBoundary = b; showLayers = false },
                onImport = { showLayers = false; importLauncher.launch(arrayOf("*/*")) },
            )
        }
    }
    editingBoundary?.let { entity ->
        BoundaryColorEditDialog(
            entity = entity,
            onDismiss = { editingBoundary = null },
            onApply = { fill, border, label -> vm.updateBoundaryStyle(entity, fill, border, label); editingBoundary = null },
        )
    }
    if (showDownload) {
        mapViewRef?.let { MapDownloadDialog(mapView = it, onDismiss = { showDownload = false }) }
    }
}

private fun openDirections(context: Context, lat: Double, lng: Double) {
    val uri = Uri.parse("google.navigation:q=$lat,$lng")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try { context.startActivity(intent) } catch (_: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")))
    }
}

@Composable
private fun MapCompass(azimuth: Float, onResetNorth: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).shadow(6.dp, CircleShape, clip = false).background(Color.White, CircleShape)
            .clip(CircleShape).clickable(onClick = onResetNorth),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).rotate(-azimuth)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = center.x; val cy = center.y; val h = size.height; val w = size.width
                drawPath(Path().apply {
                    moveTo(cx, cy - h * 0.47f); lineTo(cx - w * 0.13f, cy + h * 0.05f); lineTo(cx, cy + h * 0.14f); lineTo(cx + w * 0.13f, cy + h * 0.05f); close()
                }, color = Color(0xFFD32F2F))
                drawPath(Path().apply {
                    moveTo(cx, cy + h * 0.47f); lineTo(cx - w * 0.13f, cy - h * 0.05f); lineTo(cx, cy - h * 0.14f); lineTo(cx + w * 0.13f, cy - h * 0.05f); close()
                }, color = Color(0xFFBDBDBD))
                drawCircle(Color.White, radius = w * 0.11f, center = center)
                drawCircle(Color(0xFF424242), radius = w * 0.07f, center = center)
            }
            Text("N", color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 1.dp))
        }
    }
}
