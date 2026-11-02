package com.taptrack.app.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.views.MapView
import kotlin.math.*

@Composable
fun MapDownloadDialog(
    mapView: MapView,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val currentZoom = mapView.zoomLevelDouble.toInt()
    var minZoom by remember { mutableIntStateOf(currentZoom.coerceIn(1, 16)) }
    var maxZoom by remember { mutableIntStateOf((currentZoom + 2).coerceIn(1, 18)) }
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var totalTiles by remember { mutableIntStateOf(0) }
    var downloadComplete by remember { mutableStateOf(false) }
    var downloadErrors by remember { mutableIntStateOf(0) }

    val estimatedTiles = remember(minZoom, maxZoom) {
        val bbox = mapView.boundingBox ?: return@remember 0
        (minZoom..maxZoom).sumOf { z ->
            val n = 2.0.pow(z.toDouble()).toInt()
            val xMin = floor((bbox.lonWest + 180.0) / 360.0 * n).toInt()
            val xMax = floor((bbox.lonEast + 180.0) / 360.0 * n).toInt()
            val latNRad = Math.toRadians(bbox.latNorth)
            val latSRad = Math.toRadians(bbox.latSouth)
            val yMin = floor((1.0 - ln(tan(latNRad) + 1.0 / cos(latNRad)) / PI) / 2.0 * n).toInt()
            val yMax = floor((1.0 - ln(tan(latSRad) + 1.0 / cos(latSRad)) / PI) / 2.0 * n).toInt()
            ((xMax - xMin + 1) * (yMax - yMin + 1)).coerceAtLeast(0)
        }
    }

    val tooManyTiles = estimatedTiles > 5_000

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        icon = {
            Icon(
                if (downloadComplete) Icons.Default.CheckCircle else Icons.Default.Download,
                contentDescription = null,
                tint = if (downloadComplete) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface
            )
        },
        title = {
            Text(if (downloadComplete) "Download Complete" else "Download Map Area")
        },
        text = {
            if (downloadComplete) {
                Text("The current map area is now saved for offline use.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Download the visible map area for offline use, similar to OsmAnd offline maps.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Min zoom slider
                    Column {
                        Text(
                            "Min zoom level: $minZoom",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = minZoom.toFloat(),
                            onValueChange = {
                                minZoom = it.toInt().coerceAtMost(maxZoom)
                            },
                            valueRange = 1f..18f,
                            steps = 16,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Max zoom slider
                    Column {
                        Text(
                            "Max zoom level: $maxZoom",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = maxZoom.toFloat(),
                            onValueChange = {
                                maxZoom = it.toInt().coerceAtLeast(minZoom)
                            },
                            valueRange = 1f..18f,
                            steps = 16,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Estimated tile count
                    Surface(
                        color = if (tooManyTiles)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (tooManyTiles) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                text = if (tooManyTiles)
                                    "~$estimatedTiles tiles — reduce the zoom range"
                                else
                                    "~$estimatedTiles tiles (~${estimatedTiles * 20 / 1024} MB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (tooManyTiles)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    // Download progress
                    if (isDownloading) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = {
                                    if (totalTiles > 0) progress.toFloat() / totalTiles else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "$progress / $totalTiles tiles downloaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (downloadErrors > 0) {
                        Text(
                            "$downloadErrors tile(s) failed to download",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (downloadComplete) {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(
                    onClick = {
                        isDownloading = true
                        progress = 0
                        totalTiles = 0
                        downloadErrors = 0
                        val bbox = mapView.boundingBox
                        val cacheManager = CacheManager(mapView)
                        cacheManager.downloadAreaAsync(
                            context,
                            bbox,
                            minZoom,
                            maxZoom,
                            object : CacheManager.CacheManagerCallback {
                                override fun onTaskComplete() {
                                    mainHandler.post {
                                        isDownloading = false
                                        downloadComplete = true
                                    }
                                }
                                override fun onTaskFailed(errors: Int) {
                                    mainHandler.post {
                                        isDownloading = false
                                        downloadErrors = errors
                                    }
                                }
                                override fun updateProgress(
                                    progress_: Int,
                                    currentZoomLevel: Int,
                                    zoomMin: Int,
                                    zoomMax: Int
                                ) {
                                    mainHandler.post { progress = progress_ }
                                }
                                override fun downloadStarted() {}
                                override fun setPossibleTilesInArea(total: Int) {
                                    mainHandler.post { totalTiles = total }
                                }
                            }
                        )
                    },
                    enabled = !isDownloading && !tooManyTiles && estimatedTiles > 0
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading…")
                    } else {
                        Text("Download")
                    }
                }
            }
        },
        dismissButton = {
            if (!downloadComplete) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isDownloading
                ) { Text("Cancel") }
            }
        }
    )
}
