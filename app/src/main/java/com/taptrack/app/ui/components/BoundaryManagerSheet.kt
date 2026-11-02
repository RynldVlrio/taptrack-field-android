package com.taptrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taptrack.app.data.local.entity.BoundaryEntity

@Composable
fun BoundaryManagerSheet(
    boundaries: List<BoundaryEntity>,
    onToggleVisibility: (BoundaryEntity) -> Unit,
    onDelete: (BoundaryEntity) -> Unit,
    onEditStyle: (BoundaryEntity) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Map Layers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = onImport,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import Layer", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "Supported: shapefile (.zip or .shp), KMZ, KML, GPX  •  Export from QGIS in WGS84 (EPSG:4326)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        if (boundaries.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "No layers yet. Tap \"Import Layer\" to add a boundary from QGIS, KMZ, or GPX.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(boundaries, key = { it.id }) { boundary ->
                    BoundaryLayerRow(
                        boundary = boundary,
                        onToggle = { onToggleVisibility(boundary) },
                        onEditStyle = { onEditStyle(boundary) },
                        onDelete = { onDelete(boundary) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BoundaryLayerRow(
    boundary: BoundaryEntity,
    onToggle: () -> Unit,
    onEditStyle: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Color swatch: fill inside, border outside
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(Color(boundary.fillColor), CircleShape)
                    .border(2.dp, Color(boundary.borderColor), CircleShape)
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = boundary.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = fileTypeLabel(boundary.fileType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(checked = boundary.isVisible, onCheckedChange = { onToggle() }, modifier = Modifier.height(24.dp))

            IconButton(onClick = onEditStyle, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Palette, contentDescription = "Edit style", modifier = Modifier.size(18.dp))
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete layer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun fileTypeLabel(fileType: String) = when (fileType) {
    "SHAPEFILE" -> "Shapefile (ZIP)"
    "SHP"       -> "Shapefile (.shp)"
    "KMZ"       -> "KMZ / Google Earth"
    "KML"       -> "KML"
    "GPX"       -> "GPX Track"
    else        -> fileType
}
