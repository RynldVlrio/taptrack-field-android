package com.taptrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taptrack.app.data.local.entity.BoundaryEntity

private val PALETTE = listOf(
    0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(), 0xFF3F51B5.toInt(),
    0xFF2196F3.toInt(), 0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(), 0xFF009688.toInt(), 0xFF4CAF50.toInt(),
    0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(),
    0xFFFF5722.toInt(), 0xFF795548.toInt(), 0xFF9E9E9E.toInt(), 0xFF607D8B.toInt(), 0xFF000000.toInt()
)

@Composable
fun BoundaryColorEditDialog(
    entity: BoundaryEntity,
    onDismiss: () -> Unit,
    onApply: (fillColor: Int, borderColor: Int, showLabel: Boolean) -> Unit
) {
    // Extract alpha from fillColor (unsigned shift)
    val initFillAlpha = (entity.fillColor ushr 24) / 255f
    val initFillRgb   = entity.fillColor and 0x00FFFFFF
    val initBorderRgb = entity.borderColor and 0x00FFFFFF

    var selectedTab by remember { mutableIntStateOf(0) }   // 0=Fill, 1=Border
    var fillRgb     by remember { mutableIntStateOf(initFillRgb) }
    var fillAlpha   by remember { mutableFloatStateOf(initFillAlpha.coerceIn(0f, 1f)) }
    var borderRgb   by remember { mutableIntStateOf(initBorderRgb) }
    var showLabel   by remember { mutableStateOf(entity.showLabel) }

    val previewFill   = Color((fillAlpha * 255).toInt() shl 24 or fillRgb)
    val previewBorder = Color(0xFF000000.toInt() or borderRgb)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val finalFill   = (fillAlpha * 255).toInt() shl 24 or fillRgb
                val finalBorder = 0xFF000000.toInt() or borderRgb
                onApply(finalFill, finalBorder, showLabel)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit Layer Style", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Preview swatch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(160.dp)
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(previewFill)
                            .border(3.dp, previewBorder, RoundedCornerShape(6.dp))
                    )
                }

                // Tab selector
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("Fill", modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("Border", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                // Color grid
                val columns = 5
                val currentRgb = if (selectedTab == 0) fillRgb else borderRgb
                PALETTE.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { argb ->
                            val rgb = argb and 0x00FFFFFF
                            val isSelected = rgb == currentRgb
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF000000.toInt() or rgb))
                                    .then(
                                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier.border(1.dp, Color.LightGray, CircleShape)
                                    )
                                    .clickable {
                                        if (selectedTab == 0) fillRgb = rgb
                                        else borderRgb = rgb
                                    }
                            )
                        }
                    }
                }

                // Opacity slider (fill only)
                if (selectedTab == 0) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Fill opacity", style = MaterialTheme.typography.bodySmall)
                            Text("${(fillAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = fillAlpha,
                            onValueChange = { fillAlpha = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Show label toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Show layer name on map", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = showLabel, onCheckedChange = { showLabel = it })
                }
            }
        }
    )
}
