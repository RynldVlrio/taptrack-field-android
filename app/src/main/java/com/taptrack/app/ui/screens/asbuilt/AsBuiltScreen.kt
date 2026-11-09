package com.taptrack.app.ui.screens.asbuilt

import android.location.Location
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.taptrack.app.AppContainer
import com.taptrack.app.ui.components.OsmMapView
import com.taptrack.app.ui.screens.map.MapViewModel
import com.taptrack.app.ui.theme.Web
import com.taptrack.app.utils.KmlExporter
import com.taptrack.app.utils.PipeDiameter
import com.taptrack.app.utils.STANDARD_PIPE_DIAMETERS
import com.taptrack.app.utils.locationFlow
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
    val vm: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val boundaryOverlays by vm.overlays.collectAsStateWithLifecycle()

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

    // Map display states
    var satellite by remember { mutableStateOf(false) }
    var locateTrigger by remember { mutableIntStateOf(0) }

    // Survey parameters
    var selectedDiameter by remember { mutableStateOf(STANDARD_PIPE_DIAMETERS[2]) } // 4 in. default
    var pipeName by remember { mutableStateOf("Mainline Segment 1") }
    var isRecording by remember { mutableStateOf(false) }
    var recordedPoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var totalDistanceM by remember { mutableDoubleStateOf(0.0) }
    var currentAccuracyM by remember { mutableFloatStateOf(0f) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    // Dialog & sheet states
    val scope = rememberCoroutineScope()
    var showConfirmFinishDialog by remember { mutableStateOf(false) }
    var savedKmlFile by remember { mutableStateOf<File?>(null) }
    var uploadStatusMsg by remember { mutableStateOf<String?>(null) }
    var showSavedSurveysSheet by remember { mutableStateOf(false) }
    var diameterDropdownExpanded by remember { mutableStateOf(false) }

    // Live GPS recording flow
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
            boundaryOverlays = boundaryOverlays,
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

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Satellite Toggle
                        IconButton(
                            onClick = { satellite = !satellite },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (satellite) Web.Teal50 else Color.Transparent,
                                contentColor = if (satellite) Web.Teal700 else Web.Slate600
                            )
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = "Toggle Satellite")
                        }

                        // Locate Me Button
                        IconButton(
                            onClick = { locateTrigger++ },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Web.Slate600)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Locate Me")
                        }

                        // Saved Surveys History
                        IconButton(
                            onClick = { showSavedSurveysSheet = true },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Web.Slate600)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = "Saved Surveys")
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
                        val currentPts = recordedPoints
                        val currentDist = totalDistanceM
                        val currentName = pipeName
                        val currentDia = selectedDiameter

                        val file = KmlExporter.exportPipelineToKml(
                            context = context,
                            name = currentName,
                            diameter = currentDia,
                            points = currentPts,
                            distanceMeters = currentDist
                        )
                        savedKmlFile = file
                        uploadStatusMsg = "Uploading to Supabase..."

                        scope.launch {
                            val res = KmlExporter.uploadToSupabase(
                                container = container,
                                pipeName = currentName,
                                diameter = currentDia,
                                points = currentPts,
                                distanceMeters = currentDist,
                                file = file
                            )
                            val err = res.exceptionOrNull()
                            when {
                                res.isSuccess -> {
                                    uploadStatusMsg = "Synced to Supabase (as_built_pipelines)"
                                    Toast.makeText(context, "Saved & synced to Supabase!", Toast.LENGTH_LONG).show()
                                }
                                err is java.io.IOException -> {
                                    uploadStatusMsg = "Saved on this phone — it uploads automatically when online"
                                    Toast.makeText(context, "No signal — saved. It uploads automatically when online.", Toast.LENGTH_LONG).show()
                                }
                                else -> {
                                    uploadStatusMsg = "Saved in Downloads. Supabase sync: ${err?.message ?: "Upload failed"} — retried at next sync"
                                    Toast.makeText(context, "Saved locally. Supabase error: ${err?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Web.Teal700)
                ) {
                    Text("Save & Sync KML")
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
                    Text("File saved in Downloads/TapTrack/AsBuilt:", style = MaterialTheme.typography.labelMedium, color = Web.Slate500)
                    Text(file.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        String.format(Locale.US, "Surveyed %.1f meters across %d points.", totalDistanceM, recordedPoints.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Web.Slate600
                    )
                    uploadStatusMsg?.let { status ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.contains("Synced")) Color(0xFF16A34A) else Web.Slate600,
                            fontWeight = FontWeight.Medium
                        )
                    }
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

    // ── Saved Surveys Bottom Sheet ──────────────────────────────────────────────
    if (showSavedSurveysSheet) {
        val savedFiles = remember(showSavedSurveysSheet) { KmlExporter.listSavedSurveys(context) }
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
                    Text("Saved As-Built Surveys (${savedFiles.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showSavedSurveysSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = "Stored in Downloads/TapTrack/AsBuilt and app storage",
                    style = MaterialTheme.typography.labelSmall,
                    color = Web.Slate500
                )

                Spacer(Modifier.height(8.dp))

                if (savedFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No surveys saved yet. Tap 'Start Survey' to record a pipeline.", color = Web.Slate500)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(savedFiles) { f ->
                            val dateStr = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date(f.lastModified()))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Web.Slate50,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(dateStr, style = MaterialTheme.typography.labelSmall, color = Web.Slate500)
                                    }

                                    Row {
                                        // Upload to Supabase button
                                        IconButton(onClick = {
                                            scope.launch {
                                                Toast.makeText(context, "Syncing ${f.name} to Supabase...", Toast.LENGTH_SHORT).show()
                                                val res = KmlExporter.uploadExistingFileToSupabase(container, f)
                                                if (res.getOrNull() == KmlExporter.ALREADY_UPLOADED) {
                                                    Toast.makeText(context, "Already uploaded — nothing sent again.", Toast.LENGTH_LONG).show()
                                                } else if (res.isSuccess) {
                                                    Toast.makeText(context, "Uploaded to Supabase as_built_pipelines!", Toast.LENGTH_LONG).show()
                                                } else if (res.exceptionOrNull() is java.io.IOException) {
                                                    Toast.makeText(context, "No signal — it uploads automatically when online.", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "Upload failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Default.CloudUpload, contentDescription = "Sync to Supabase", tint = Web.Teal700)
                                        }

                                        // Share
                                        IconButton(onClick = { KmlExporter.shareKml(context, f) }) {
                                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Web.Teal700)
                                        }

                                        // Delete
                                        IconButton(onClick = {
                                            f.delete()
                                            showSavedSurveysSheet = false
                                            Toast.makeText(context, "Deleted ${f.name}", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFDC2626))
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
