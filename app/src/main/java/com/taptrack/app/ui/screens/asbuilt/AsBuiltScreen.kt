package com.taptrack.app.ui.screens.asbuilt

import android.location.Location
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.MapLayerEntity
import com.taptrack.app.ui.components.OsmMapView
import com.taptrack.app.ui.screens.map.BoundaryOverlay
import com.taptrack.app.ui.screens.map.MapViewModel
import com.taptrack.app.ui.theme.Web
import com.taptrack.app.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AsBuiltScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val layers by vm.layers.collectAsStateWithLifecycle()
    val allOverlays by vm.allOverlays.collectAsStateWithLifecycle()

    // Location permissions
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    )
    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    // ── Independent As-Built Layer & Display Preferences ──────────────────────
    val prefs = remember { context.getSharedPreferences("tt_as_built_prefs", android.content.Context.MODE_PRIVATE) }

    var satellite by remember { mutableStateOf(prefs.getBoolean("as_built_satellite", false)) }
    val onToggleSatellite: (Boolean) -> Unit = { enabled ->
        satellite = enabled
        prefs.edit().putBoolean("as_built_satellite", enabled).apply()
    }

    var showSurveyedLines by remember { mutableStateOf(prefs.getBoolean("as_built_show_surveyed", true)) }
    val onToggleShowSurveyedLines: (Boolean) -> Unit = { enabled ->
        showSurveyedLines = enabled
        prefs.edit().putBoolean("as_built_show_surveyed", enabled).apply()
    }

    var initializedLayers by remember { mutableStateOf(prefs.contains("as_built_visible_layer_ids")) }
    var asBuiltVisibleLayerIds by remember {
        val saved = prefs.getStringSet("as_built_visible_layer_ids", null)
        mutableStateOf(saved?.toSet() ?: emptySet())
    }

    // On first run before user customizes As-Built layers, default to layers with defaultVisible == true
    LaunchedEffect(layers, initializedLayers) {
        if (!initializedLayers && layers.isNotEmpty()) {
            val defaults = layers.filter { it.defaultVisible && it.localFile != null }.map { it.id }.toSet()
            asBuiltVisibleLayerIds = defaults
            prefs.edit().putStringSet("as_built_visible_layer_ids", defaults).apply()
            initializedLayers = true
        }
    }

    val onToggleAsBuiltLayer: (String) -> Unit = { layerId ->
        val next = if (layerId in asBuiltVisibleLayerIds) {
            asBuiltVisibleLayerIds - layerId
        } else {
            asBuiltVisibleLayerIds + layerId
        }
        asBuiltVisibleLayerIds = next
        prefs.edit().putStringSet("as_built_visible_layer_ids", next).apply()
    }

    var locateTrigger by remember { mutableIntStateOf(0) }
    var searchTarget by remember { mutableStateOf<GeoPoint?>(null) }

    // Surveyed pipelines lists (Supabase cloud database + local phone storage)
    var localSurveys by remember { mutableStateOf<List<SurveyedPipeline>>(emptyList()) }
    var cloudSurveys by remember { mutableStateOf<List<SurveyedPipeline>>(emptyList()) }
    var isLoadingCloudSurveys by remember { mutableStateOf(false) }
    var refreshSurveysTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshSurveysTrigger) {
        // Load locally saved files immediately
        localSurveys = KmlExporter.loadAllSurveyedPipelines(context)
        // Fetch surveys from Supabase public.as_built_pipelines table
        isLoadingCloudSurveys = true
        cloudSurveys = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            KmlExporter.fetchSupabaseSurveys(container, context)
        }
        isLoadingCloudSurveys = false
    }

    // Merged list: Supabase database records + any unsynced local surveys
    val allSurveyedPipelines = remember(cloudSurveys, localSurveys) {
        val cloudPaths = cloudSurveys.map { it.storagePath.substringAfterLast("/") }.filter { it.isNotBlank() }.toSet()
        val cloudIds = cloudSurveys.map { it.id }.filter { it.isNotBlank() }.toSet()
        val pendingLocal = localSurveys.filter { local ->
            local.file?.name !in cloudPaths && local.id !in cloudIds
        }
        cloudSurveys + pendingLocal
    }

    // Latest locally saved survey ("Previous Survey")
    val previousLocalSurvey = remember(localSurveys) {
        localSurveys.maxByOrNull { it.lastModified }
    }

    // Active live survey parameters
    var selectedDiameter by remember { mutableStateOf(STANDARD_PIPE_DIAMETERS[2]) } // 4 in. default
    var pipeName by remember { mutableStateOf("Mainline Segment 1") }
    var isRecording by remember { mutableStateOf(false) }
    var recordedPoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var totalDistanceM by remember { mutableDoubleStateOf(0.0) }
    var currentAccuracyM by remember { mutableFloatStateOf(0f) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    // Dialog & sheet states
    var showLayerSettingsDialog by remember { mutableStateOf(false) }
    var showSavedSurveysSheet by remember { mutableStateOf(false) }
    var showConfirmFinishDialog by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<SurveyedPipeline?>(null) }
    var savedKmlFile by remember { mutableStateOf<File?>(null) }
    var diameterDropdownExpanded by remember { mutableStateOf(false) }

    // ── Build Boundary Overlays for Map ───────────────────────────────────────
    // Display surveyed pipelines from Supabase database as a dedicated map overlay
    val surveyedOverlay = remember(allSurveyedPipelines, showSurveyedLines) {
        if (!showSurveyedLines || allSurveyedPipelines.isEmpty()) null
        else {
            val features = allSurveyedPipelines.map { survey ->
                val distStr = if (survey.lengthMeters >= 1000.0) {
                    String.format(Locale.US, "%.2f km", survey.lengthMeters / 1000.0)
                } else {
                    String.format(Locale.US, "%.1f m", survey.lengthMeters)
                }
                val label = if (survey.surveyorName.isNullOrBlank()) {
                    "${survey.name} · ${survey.diameter.label} ($distStr)"
                } else {
                    "${survey.name} · ${survey.diameter.label} ($distStr) - ${survey.surveyorName}"
                }
                NamedFeature(
                    name = label,
                    rings = listOf(survey.points),
                    color = survey.diameter.colorInt
                )
            }
            BoundaryOverlay(
                id = 888888L,
                name = "Surveyed Pipelines (Supabase DB)",
                fillColor = 0,
                borderColor = android.graphics.Color.GREEN,
                showLabel = false,
                polygons = emptyList(),
                polylines = features,
                points = emptyList(),
                weight = 5f
            )
        }
    }

    val displayOverlays = remember(allOverlays, asBuiltVisibleLayerIds, surveyedOverlay) {
        val list = mutableListOf<BoundaryOverlay>()
        for (id in asBuiltVisibleLayerIds) {
            val overlay = allOverlays[id]
            if (overlay != null) {
                list.add(overlay)
            }
        }
        if (surveyedOverlay != null) {
            list.add(surveyedOverlay)
        }
        list
    }

    // ── Live GPS recording flow ───────────────────────────────────────────────
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        context.locationFlow().collect { loc ->
            // Filter out poor accuracy fixes (> 15m)
            if (loc.hasAccuracy() && loc.accuracy > 15f && loc.accuracy > 0f) {
                currentAccuracyM = loc.accuracy
                return@collect
            }
            currentAccuracyM = if (loc.hasAccuracy()) loc.accuracy else 3f

            val newPoint = GeoPoint(loc.latitude, loc.longitude)

            val lastPoint = recordedPoints.lastOrNull()
            if (lastPoint != null) {
                val dist = FloatArray(1)
                Location.distanceBetween(
                    lastPoint.latitude, lastPoint.longitude,
                    newPoint.latitude, newPoint.longitude,
                    dist
                )
                // Filter jitter: ignore moves smaller than 1.5 meters
                if (dist[0] < 1.5f) return@collect
                totalDistanceM += dist[0].toDouble()
            }
            recordedPoints = recordedPoints + newPoint
        }
    }

    // Elapsed timer while recording
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            elapsedSeconds++
        }
    }

    // Pulsing animation for the recording indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // ── Map View ────────────────────────────────────────────────────────────
        OsmMapView(
            pins = emptyList(),
            modifier = Modifier.fillMaxSize(),
            satellite = satellite,
            locateTrigger = locateTrigger,
            searchTarget = searchTarget,
            boundaryOverlays = displayOverlays,
            routePoints = recordedPoints,
            routeColor = selectedDiameter.colorInt,
            routeWidthDp = 6f,
        )

        // ── Top Toolbar ─────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) Color.Red.copy(alpha = pulseAlpha) else Web.Teal600)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "As-Built Pipeline Survey",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Web.Slate900
                            )
                            Text(
                                text = if (isRecording) "RECORDING GPS TRACK..." else "Ready to survey",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isRecording) Color.Red else Web.Slate500,
                                fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Separate Satellite Toggle Button
                        IconButton(
                            onClick = { onToggleSatellite(!satellite) },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (satellite) Web.Teal100 else Color.Transparent,
                                contentColor = if (satellite) Web.Teal800 else Web.Slate600
                            )
                        ) {
                            Icon(Icons.Default.Public, contentDescription = "Toggle Satellite")
                        }

                        // Dedicated Layer Settings Button
                        val hasActiveLayers = showSurveyedLines || asBuiltVisibleLayerIds.isNotEmpty()
                        IconButton(
                            onClick = { showLayerSettingsDialog = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (hasActiveLayers) Web.Teal50 else Color.Transparent,
                                contentColor = if (hasActiveLayers) Web.Teal700 else Web.Slate600
                            )
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = "Layer Settings")
                        }

                        // Locate Me Button
                        IconButton(
                            onClick = { locateTrigger++ },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Web.Slate600)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Locate Me")
                        }

                        // Surveyed Items List Button with Badge
                        IconButton(
                            onClick = { showSavedSurveysSheet = true },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Web.Slate600)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (allSurveyedPipelines.isNotEmpty()) {
                                        Badge(
                                            containerColor = Web.Teal700,
                                            contentColor = Color.White
                                        ) {
                                            Text("${allSurveyedPipelines.size}")
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = "Surveyed Pipelines")
                            }
                        }
                    }
                }
            }
        }

        // ── Floating Survey Control Card (Bottom) ───────────────────────────────
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Live Metrics Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Web.Slate100, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Distance
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (totalDistanceM >= 1000.0) {
                                String.format(Locale.US, "%.2f km", totalDistanceM / 1000.0)
                            } else {
                                String.format(Locale.US, "%.1f m", totalDistanceM)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Web.Slate900
                        )
                        Text("Distance", style = MaterialTheme.typography.labelSmall, color = Web.Slate500)
                    }

                    // Divider
                    Box(Modifier.width(1.dp).height(24.dp).background(Web.Slate300))

                    // Points Count
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${recordedPoints.size} pts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Web.Slate900
                        )
                        Text("Breadcrumbs", style = MaterialTheme.typography.labelSmall, color = Web.Slate500)
                    }

                    // Divider
                    Box(Modifier.width(1.dp).height(24.dp).background(Web.Slate300))

                    // Accuracy / Status
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val accText = if (currentAccuracyM > 0f) String.format(Locale.US, "±%.1fm", currentAccuracyM) else "GPS Idle"
                        val accColor = when {
                            currentAccuracyM in 0.1f..5f -> Color(0xFF16A34A) // green
                            currentAccuracyM in 5f..15f -> Color(0xFFCA8A04) // amber
                            else -> Web.Slate500
                        }
                        Text(
                            text = accText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = accColor
                        )
                        Text("Accuracy", style = MaterialTheme.typography.labelSmall, color = Web.Slate500)
                    }

                    // Elapsed Timer (if active)
                    if (isRecording) {
                        Box(Modifier.width(1.dp).height(24.dp).background(Web.Slate300))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val mins = elapsedSeconds / 60
                            val secs = elapsedSeconds % 60
                            Text(
                                text = String.format(Locale.US, "%02d:%02d", mins, secs),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Red
                            )
                            Text("Time", style = MaterialTheme.typography.labelSmall, color = Color.Red)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Pipe Specification Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pipe Diameter Dropdown
                    Box(modifier = Modifier.weight(1.1f)) {
                        OutlinedCard(
                            onClick = { if (!isRecording) diameterDropdownExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isRecording) Web.Slate50 else Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(selectedDiameter.composeColor)
                                            .border(1.dp, Web.Slate300, CircleShape)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = selectedDiameter.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Web.Slate500)
                            }
                        }

                        DropdownMenu(
                            expanded = diameterDropdownExpanded,
                            onDismissRequest = { diameterDropdownExpanded = false }
                        ) {
                            STANDARD_PIPE_DIAMETERS.forEach { d ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(d.composeColor)
                                                    .border(1.dp, Web.Slate300, CircleShape)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(d.label, fontWeight = if (d == selectedDiameter) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    },
                                    onClick = {
                                        selectedDiameter = d
                                        diameterDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Segment / Pipe Name Input
                    OutlinedTextField(
                        value = pipeName,
                        onValueChange = { pipeName = it },
                        label = { Text("Pipe Name") },
                        singleLine = true,
                        enabled = !isRecording,
                        modifier = Modifier.weight(1.0f)
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Previous survey indicator with delete button for locally saved survey data
                if (!isRecording && previousLocalSurvey != null) {
                    val prevDistStr = if (previousLocalSurvey.lengthMeters >= 1000.0) {
                        String.format(Locale.US, "%.2f km", previousLocalSurvey.lengthMeters / 1000.0)
                    } else {
                        String.format(Locale.US, "%.1f m", previousLocalSurvey.lengthMeters)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Web.Slate50,
                        border = BorderStroke(1.dp, Web.Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(previousLocalSurvey.diameter.composeColor)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Previous Survey: ${previousLocalSurvey.name}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${previousLocalSurvey.diameter.label} · $prevDistStr · Saved on phone",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Web.Slate500
                                    )
                                }
                            }
                            // Delete button for previous survey
                            OutlinedButton(
                                onClick = { itemToDelete = previousLocalSurvey },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                                border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete previous survey", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete Local", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Action Buttons: Start / Stop Survey
                if (!isRecording) {
                    Button(
                        onClick = {
                            if (!locationPermissions.allPermissionsGranted) {
                                locationPermissions.launchMultiplePermissionRequest()
                                return@Button
                            }
                            // Reset previous survey buffer
                            recordedPoints = emptyList()
                            totalDistanceM = 0.0
                            elapsedSeconds = 0L
                            locateTrigger++
                            isRecording = true
                            Toast.makeText(context, "Pipeline survey started. Walk along the line.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Web.Teal700),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Survey", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Discard / Reset button
                        OutlinedButton(
                            onClick = {
                                isRecording = false
                                recordedPoints = emptyList()
                                totalDistanceM = 0.0
                                elapsedSeconds = 0L
                                Toast.makeText(context, "Survey cancelled.", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(0.7f)
                                .height(50.dp)
                        ) {
                            Text("Discard", color = Web.Slate600)
                        }

                        // Stop & Finish Button
                        Button(
                            onClick = {
                                if (recordedPoints.size < 2) {
                                    Toast.makeText(context, "Need at least 2 points to save pipeline.", Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                showConfirmFinishDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Finish & Save", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ── Separate Layer Settings Bottom Sheet ────────────────────────────────────
    if (showLayerSettingsDialog) {
        ModalBottomSheet(
            onDismissRequest = { showLayerSettingsDialog = false },
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("As-Built Map Layers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Toggles here only affect this As-Built tab",
                            style = MaterialTheme.typography.bodySmall,
                            color = Web.Slate500
                        )
                    }
                    IconButton(onClick = { showLayerSettingsDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = Web.Slate200)

                // 1. Surveyed Pipelines Layer Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleShowSurveyedLines(!showSurveyedLines) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show Surveyed Pipelines (Supabase DB)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        val statusText = if (isLoadingCloudSurveys) "Syncing from Supabase..." else "${cloudSurveys.size} in Supabase DB · ${localSurveys.size} local"
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Web.Slate500
                        )
                    }
                    Switch(
                        checked = showSurveyedLines,
                        onCheckedChange = { onToggleShowSurveyedLines(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Web.Teal700, checkedTrackColor = Web.Teal100)
                    )
                }

                HorizontalDivider(color = Web.Slate200)

                // 2. Reference Layers (Web Boundaries & Pipelines)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Project Reference Layers",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Web.Slate700
                    )
                    Text(
                        text = "Independent from Map tab — toggle First 1000, Planned lines, or Barangays on/off for surveying.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Web.Slate500
                    )
                }

                if (layers.isEmpty()) {
                    Surface(color = Web.Slate100, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "No reference layers found. Ask office to add layers in web app, then sync.",
                            Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Web.Slate600
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(layers) { l ->
                            val isChecked = l.id in asBuiltVisibleLayerIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = l.localFile != null) { onToggleAsBuiltLayer(l.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(l.fillColor or 0x33000000))
                                        .border(2.dp, Color(l.strokeColor), RoundedCornerShape(3.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    if (l.localFile == null) {
                                        Text("Not downloaded yet — sync while online", style = MaterialTheme.typography.labelSmall, color = Web.Amber700)
                                    }
                                }
                                Switch(
                                    checked = isChecked,
                                    onCheckedChange = { onToggleAsBuiltLayer(l.id) },
                                    enabled = l.localFile != null,
                                    colors = SwitchDefaults.colors(checkedThumbColor = Web.Teal700, checkedTrackColor = Web.Teal100)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Web.Slate200)

                // 3. Satellite Map Imagery
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleSatellite(!satellite) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Satellite Imagery", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (satellite) "Satellite tiles active" else "Standard street map active",
                            style = MaterialTheme.typography.bodySmall,
                            color = Web.Slate500
                        )
                    }
                    Switch(
                        checked = satellite,
                        onCheckedChange = { onToggleSatellite(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Web.Teal700, checkedTrackColor = Web.Teal100)
                    )
                }
            }
        }
    }

    // ── Finish & Save Confirmation Dialog ───────────────────────────────────────
    if (showConfirmFinishDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmFinishDialog = false },
            icon = { Icon(Icons.Default.Save, contentDescription = null, tint = Web.Teal700) },
            title = { Text("Save Pipeline Survey?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Survey Summary:", fontWeight = FontWeight.SemiBold)
                    Text("• Name: $pipeName")
                    Text("• Pipe: ${selectedDiameter.label}")
                    Text(
                        text = if (totalDistanceM >= 1000.0) {
                            String.format(Locale.US, "• Total Length: %.2f km (%.1f m)", totalDistanceM / 1000.0, totalDistanceM)
                        } else {
                            String.format(Locale.US, "• Total Length: %.1f m", totalDistanceM)
                        }
                    )
                    Text("• GPS Points: ${recordedPoints.size}")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isRecording = false
                        showConfirmFinishDialog = false
                        val file = KmlExporter.exportPipelineToKml(
                            context = context,
                            name = pipeName,
                            diameter = selectedDiameter,
                            points = recordedPoints,
                            distanceMeters = totalDistanceM
                        )
                        savedKmlFile = file
                        scope.launch {
                            val r = KmlExporter.uploadToSupabase(
                                container = container,
                                pipeName = pipeName,
                                diameter = selectedDiameter,
                                points = recordedPoints,
                                distanceMeters = totalDistanceM,
                                file = file
                            )
                            if (r.isSuccess) {
                                refreshSurveysTrigger++
                            }
                        }
                        refreshSurveysTrigger++ // triggers reload so it displays on map right away
                        Toast.makeText(context, "KML saved: ${file.name}", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Web.Teal700)
                ) {
                    Text("Save KML")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmFinishDialog = false }) {
                    Text("Continue Surveying")
                }
            }
        )
    }

    // ── Export / Share Dialog After Saving ───────────────────────────────────────
    savedKmlFile?.let { file ->
        AlertDialog(
            onDismissRequest = { savedKmlFile = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(36.dp)) },
            title = { Text("Pipeline Saved Successfully") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("File created:", style = MaterialTheme.typography.labelMedium, color = Web.Slate500)
                    Text(file.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        String.format(Locale.US, "Surveyed %.1f meters across %d points.", totalDistanceM, recordedPoints.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Web.Slate600
                    )
                    Text(
                        "Displayed on map in ${selectedDiameter.label} color.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Web.Teal700,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        KmlExporter.shareKml(context, file)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Web.Teal700)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share / Export")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    savedKmlFile = null
                    // Reset for next survey
                    recordedPoints = emptyList()
                    totalDistanceM = 0.0
                    elapsedSeconds = 0L
                    pipeName = "Mainline Segment " + SimpleDateFormat("HHmm", Locale.US).format(Date())
                }) {
                    Text("Done")
                }
            }
        )
    }

    // ── Delete Confirmation Dialog for Local Survey Data ───────────────────────
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFDC2626)) },
            title = { Text("Delete Locally Saved Survey?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Are you sure you want to delete this locally saved survey data?",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("• Name: ${item.name}")
                    Text("• Pipe: ${item.diameter.label}")
                    if (item.file != null) {
                        Text("• File: ${item.file.name}")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This will permanently delete the locally saved survey data from this phone. (Cloud records in Supabase remain intact).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Web.Slate600
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (item.file != null) {
                            KmlExporter.deleteSurvey(context, item.file)
                        }
                        itemToDelete = null
                        refreshSurveysTrigger++ // Refresh map and lists
                        Toast.makeText(context, "Deleted locally saved survey: ${item.name}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete Local Data")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Delete All Locally Saved Surveys Dialog ─────────────────────────────────
    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFDC2626)) },
            title = { Text("Delete All Locally Saved Surveys?") },
            text = {
                Text(
                    "Are you sure you want to delete all locally saved survey data (${localSurveys.size} file(s)) from this phone? Cloud records in Supabase will remain safe.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = KmlExporter.deleteAllLocalSurveys(context)
                        showDeleteAllConfirm = false
                        refreshSurveysTrigger++
                        Toast.makeText(context, "Deleted $count locally saved survey(s)", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete All Local")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Saved / Surveyed Pipelines Bottom Sheet ─────────────────────────────────
    if (showSavedSurveysSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSavedSurveysSheet = false },
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Surveyed Pipelines (${allSurveyedPipelines.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val summaryText = buildString {
                            append("${cloudSurveys.size} in Supabase DB")
                            if (localSurveys.isNotEmpty()) append(" · ${localSurveys.size} local")
                            if (isLoadingCloudSurveys) append(" (syncing...)")
                        }
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (showSurveyedLines) Color(0xFF16A34A) else Web.Slate500
                        )
                    }
                    IconButton(onClick = { showSavedSurveysSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Delete All Local Surveys Button if any local files exist
                if (localSurveys.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showDeleteAllConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete All Locally Saved Surveys (${localSurveys.size})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (allSurveyedPipelines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No surveys found in Supabase or local storage.", color = Web.Slate500)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allSurveyedPipelines) { item ->
                            val distStr = if (item.lengthMeters >= 1000.0) {
                                String.format(Locale.US, "%.2f km", item.lengthMeters / 1000.0)
                            } else {
                                String.format(Locale.US, "%.1f m", item.lengthMeters)
                            }
                            val dateStr = if (item.surveyDate.isNotBlank()) item.surveyDate else SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(item.lastModified))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Web.Slate50,
                                border = BorderStroke(1.dp, Web.Slate200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Name & Diameter Badge
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(item.diameter.composeColor)
                                                    .border(1.dp, Web.Slate300, CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = item.name,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    if (item.isCloud) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = Web.Teal50,
                                                            border = BorderStroke(1.dp, Web.Teal100)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(Icons.Default.Cloud, contentDescription = null, tint = Web.Teal700, modifier = Modifier.size(10.dp))
                                                                Spacer(Modifier.width(2.dp))
                                                                Text("Cloud DB", style = MaterialTheme.typography.labelSmall, color = Web.Teal800, fontSize = 9.sp)
                                                            }
                                                        }
                                                    }
                                                    if (item.file != null) {
                                                        Spacer(Modifier.width(4.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = Web.Slate100,
                                                            border = BorderStroke(1.dp, Web.Slate300)
                                                        ) {
                                                            Text("Local KML", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Web.Slate700, fontSize = 9.sp)
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "${item.diameter.label} · $distStr" + if (!item.surveyorName.isNullOrBlank()) " · ${item.surveyorName}" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Web.Slate600
                                                )
                                            }
                                        }

                                        // DELETE BUTTON for locally saved survey data
                                        if (item.file != null) {
                                            IconButton(
                                                onClick = { itemToDelete = item },
                                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFFDC2626))
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete locally saved data")
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$dateStr · ${item.pointsCount} pts",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Web.Slate500
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            // View / Pan to this pipeline on map
                                            if (item.points.isNotEmpty()) {
                                                IconButton(
                                                    onClick = {
                                                        searchTarget = item.points.first()
                                                        showSavedSurveysSheet = false
                                                        Toast.makeText(context, "Focused on ${item.name}", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Visibility,
                                                        contentDescription = "Show on map",
                                                        tint = Web.Teal700,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                            // Sync to Supabase (if local file exists and not yet verified on cloud)
                                            if (item.file != null && !item.isCloud) {
                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            Toast.makeText(context, "Syncing ${item.name}...", Toast.LENGTH_SHORT).show()
                                                            val res = KmlExporter.uploadExistingFileToSupabase(container, item.file)
                                                            if (res.getOrNull() == KmlExporter.ALREADY_UPLOADED) {
                                                                Toast.makeText(context, "Already uploaded to cloud.", Toast.LENGTH_LONG).show()
                                                            } else if (res.isSuccess) {
                                                                Toast.makeText(context, "Synced to Supabase as_built_pipelines!", Toast.LENGTH_LONG).show()
                                                                refreshSurveysTrigger++
                                                            } else if (res.exceptionOrNull() is java.io.IOException) {
                                                                Toast.makeText(context, "No signal — will auto-sync when online.", Toast.LENGTH_LONG).show()
                                                            } else {
                                                                Toast.makeText(context, "Upload failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.CloudUpload,
                                                        contentDescription = "Sync",
                                                        tint = Web.Teal700,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                            // Share (if local file exists)
                                            if (item.file != null) {
                                                IconButton(
                                                    onClick = { KmlExporter.shareKml(context, item.file) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Share,
                                                        contentDescription = "Share",
                                                        tint = Web.Teal700,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
