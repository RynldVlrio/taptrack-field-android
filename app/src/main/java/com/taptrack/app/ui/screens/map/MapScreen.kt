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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.data.local.MapLayerEntity
import com.taptrack.app.data.local.visible
import com.taptrack.app.ui.TsKind
import com.taptrack.app.ui.AppStatus
import com.taptrack.app.ui.fullName
import com.taptrack.app.ui.holdsSlot
import com.taptrack.app.ui.TsStatus
import com.taptrack.app.ui.components.MapDownloadDialog
import com.taptrack.app.ui.components.MapPin
import com.taptrack.app.ui.components.MapSearchBar
import com.taptrack.app.ui.components.OsmMapView
import com.taptrack.app.ui.kind
import com.taptrack.app.ui.label
import com.taptrack.app.ui.theme.Web
import com.taptrack.app.utils.RouteResult
import com.taptrack.app.utils.fetchRoute
import com.taptrack.app.utils.formatCoordinates
import com.taptrack.app.utils.formatDistance
import com.taptrack.app.utils.formatDuration
import com.taptrack.app.utils.getLastKnownLocation
import com.taptrack.app.utils.statusBadgeBitmap
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    container: AppContainer,
    onOpenTapstand: (String) -> Unit,
    onAddTapstand: (Double, Double) -> Unit,
    onOpenApplicant: (String) -> Unit = {},
    onAddApplicant: (lat: Double?, lng: Double?, tapstandId: String?) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val vm: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val tapstands by vm.tapstands.collectAsStateWithLifecycle()
    val barangays by vm.barangays.collectAsStateWithLifecycle()
    val layers by vm.layers.collectAsStateWithLifecycle()
    val applicants by vm.applicants.collectAsStateWithLifecycle()
    val boundaryOverlays by vm.overlays.collectAsStateWithLifecycle()
    val canEdit = container.session.canEdit
    val canAdd = container.session.canAdd

    val locationPermissions = rememberMultiplePermissionsState(
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    LaunchedEffect(Unit) { if (!locationPermissions.allPermissionsGranted) locationPermissions.launchMultiplePermissionRequest() }

    val prefs = remember { context.getSharedPreferences("tt_map", Context.MODE_PRIVATE) }
    var satellite by remember { mutableStateOf(prefs.getBoolean("satellite", false)) }
    var showProposed by remember { mutableStateOf(true) }
    var showApplicants by remember { mutableStateOf(prefs.getBoolean("applicants", true)) }
    var locateTrigger by remember { mutableIntStateOf(0) }
    var searchTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var searchPin by remember { mutableStateOf<GeoPoint?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }
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

    LaunchedEffect(locationPermissions.allPermissionsGranted) { if (locationPermissions.allPermissionsGranted) locateTrigger++ }

    val pins = remember(tapstands, showProposed, applicants, showApplicants) {
        val houses = if (!showApplicants) emptyList() else applicants
            .filter { it.lat != null && it.lng != null && it.status != "cancelled" && it.status != "rejected" }
            .map { MapPin("a:" + it.id, it.lat!!, it.lng!!, AppStatus.argb(it.status), it.fullName, small = true) }
        houses + tapstands.filter { it.kind == TsKind.OFFICIAL || (showProposed && it.kind == TsKind.PROPOSED) }
            .map { MapPin(it.id, it.lat, it.lng, TsStatus.argb(it.status), it.label, status = it.status) }
    }
    val usedCount = remember(applicants) { applicants.filter { it.holdsSlot && it.assignedTapstandId != null }.groupingBy { it.assignedTapstandId!! }.eachCount() }

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
            onPinClick = { id -> if (id.startsWith("a:")) onOpenApplicant(id.removePrefix("a:")) else selected = tapstands.firstOrNull { it.id == id } },
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
            Card(Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max = 260.dp)) {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Legend", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    TsStatus.all.filter { it.key != "cancelled" }.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(s.key)
                            Spacer(Modifier.width(6.dp))
                            Text(s.label, style = MaterialTheme.typography.labelSmall)
                            if (s.key == "proposed") Text(" — to verify, not counted", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // shared layers (pipe routes, boundaries…): what each colour means
                    boundaryOverlays.forEach { o ->
                        val rows = o.legend.ifEmpty {
                            listOf(com.taptrack.app.utils.LegendRow(o.borderColor, o.name, if (o.polylines.isNotEmpty()) "line" else "area"))
                        }
                        if (!(rows.size == 1 && rows[0].label == o.name)) {
                            Text(o.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        } else Spacer(Modifier.height(2.dp))
                        rows.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LayerSwatch(r, o.weight)
                                Spacer(Modifier.width(6.dp))
                                Text(r.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End,
        ) {
            MapControl(Icons.Default.Add, "Zoom in") { mapViewRef?.controller?.zoomIn() }
            MapControl(Icons.Default.Remove, "Zoom out") { mapViewRef?.controller?.zoomOut() }
            Spacer(Modifier.height(4.dp))
            MapControl(Icons.Default.Info, "Legend", active = showLegend) { showLegend = !showLegend }
            MapControl(Icons.Default.People, if (showApplicants) "Hide applicants" else "Show applicants", active = showApplicants) {
                showApplicants = !showApplicants; prefs.edit().putBoolean("applicants", showApplicants).apply()
            }
            MapControl(Icons.Default.Flag, if (showProposed) "Hide proposed" else "Show proposed", active = showProposed) { showProposed = !showProposed }
            MapControl(Icons.Default.Satellite, "Satellite", active = satellite) { satellite = !satellite; prefs.edit().putBoolean("satellite", satellite).apply() }
            MapControl(Icons.Default.Download, "Download map area") { showDownload = true }
            MapControl(Icons.Default.Layers, "Map layers", active = layers.any { it.visible }) { showLayers = true }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).shadow(4.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .background(Web.Teal700).clickable { locateTrigger++ },
            ) { Icon(Icons.Default.MyLocation, "My location", tint = Color.White) }
        }
    }

    // ── Tapstand pin sheet ──────────────────────────────────────────────────────
    selected?.let { t ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(t.status, 20f)
                    Spacer(Modifier.width(8.dp))
                    Text(t.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(TsStatus.of(t.status).label + (barangays.firstOrNull { it.id == t.barangayId }?.let { " · ${it.name}" } ?: "") +
                    (t.sitio?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(formatCoordinates(t.lat, t.lng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.kind == TsKind.OFFICIAL) {
                    val used = usedCount[t.id] ?: 0
                    Text("Meters: $used / ${t.maxConnections}" + if (used >= t.maxConnections) "  (at design capacity)" else "",
                        style = MaterialTheme.typography.bodyMedium, color = if (used >= t.maxConnections) Web.Amber700 else Web.Slate700)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selected = null; onOpenTapstand(t.id) }) {
                        Text(if (t.kind == TsKind.PROPOSED && canEdit) "Open / verify" else "Open")
                    }
                    OutlinedButton(onClick = { selected = null; routeTo(t.lat, t.lng) }) { Text("Route") }
                    OutlinedButton(onClick = { openDirections(context, t.lat, t.lng) }) { Text("Directions") }
                }
                if (canAdd && t.kind == TsKind.OFFICIAL && t.status !in setOf("inactive", "decommissioned")) {
                    FilledTonalButton(onClick = { selected = null; onAddApplicant(null, null, t.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("New applicant on this tapstand")
                    }
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
                if (canAdd) {
                    Button(onClick = { pressPoint = null; searchPin = null; onAddTapstand(p.latitude, p.longitude) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add tapstand here")
                    }
                    FilledTonalButton(onClick = { pressPoint = null; searchPin = null; onAddApplicant(p.latitude, p.longitude, null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("New applicant (house here)")
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
            LayersSheet(layers, onToggle = { vm.toggleLayer(it) })
        }
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

/** Shared layers from the web (view-only here: just on/off on this phone). */
@Composable
private fun LayersSheet(layers: List<MapLayerEntity>, onToggle: (MapLayerEntity) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Map layers", style = MaterialTheme.typography.titleMedium)
        Text("Added and styled by the office in the web app (Settings → Map layers). They download with sync and work offline.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (layers.isEmpty()) {
            Surface(color = Web.Slate100, shape = RoundedCornerShape(8.dp)) {
                Text("No layers yet. Ask the office to add barangay boundaries or pipelines in the web app, then sync.",
                    Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, color = Web.Slate600)
            }
        }
        layers.forEach { l ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle(l) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Color(l.fillColor or 0x33000000))
                    .border(2.dp, Color(l.strokeColor), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.bodyMedium)
                    if (l.localFile == null) Text("Not downloaded yet — sync while online", style = MaterialTheme.typography.labelSmall, color = Web.Amber700)
                }
                Switch(checked = l.visible, onCheckedChange = { onToggle(l) }, enabled = l.localFile != null)
            }
        }
    }
}

/** Web-style map button: white, rounded-md, slate border; teal when switched on. */
@Composable
private fun MapControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).shadow(3.dp, shape).clip(shape)
            .background(if (active) Web.Teal50 else Color.White)
            .border(1.dp, if (active) Web.Teal600 else Web.Slate300, shape)
            .clickable(onClick = onClick),
    ) { Icon(icon, label, tint = if (active) Web.Teal700 else Web.Slate700, modifier = Modifier.size(20.dp)) }
}

/** Coloured circle with the status glyph — same as the web legend. */
@Composable
fun StatusBadge(status: String, sizeDp: Float = 16f) {
    val context = LocalContext.current
    val bmp = remember(status, sizeDp) { statusBadgeBitmap(context, status, TsStatus.argb(status), sizeDp).asImageBitmap() }
    Image(bmp, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
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

/** Legend swatch for a layer row: a line, an outlined square or a dot. */
@Composable
private fun LayerSwatch(r: com.taptrack.app.utils.LegendRow, weight: Float) {
    val c = Color(r.color)
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (r.kind) {
            "area" -> Box(Modifier.size(12.dp).border(2.dp, c, RoundedCornerShape(2.dp)).background(c.copy(alpha = 0.15f), RoundedCornerShape(2.dp)))
            "point" -> Box(Modifier.size(9.dp).background(Color.White, CircleShape).padding(1.5.dp).background(c, CircleShape))
            else -> Box(Modifier.width(16.dp).height((1f + weight * 0.5f).coerceIn(2f, 4f).dp).background(c, RoundedCornerShape(2.dp)))
        }
    }
}
