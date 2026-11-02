package com.taptrack.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun UpdateAvailableDialog(onUpdate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Available") },
        text = { Text("A new version of TapTrack is available. Update now to get the latest features and fixes.") },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

@Composable
fun ForceUpdateDialog(onUpdate: () -> Unit) {
    BackHandler(enabled = true) { /* block back press */ }
    AlertDialog(
        onDismissRequest = { /* non-dismissible */ },
        title = { Text("Update Required") },
        text = { Text("This version of TapTrack is no longer supported. Please update to continue.") },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update Now") } }
    )
}

@Composable
fun MaintenanceDialog(message: String) {
    BackHandler(enabled = true) { /* block back press */ }
    AlertDialog(
        onDismissRequest = { /* non-dismissible */ },
        title = { Text("Under Maintenance") },
        text = {
            Text(
                message.ifBlank { "TapTrack is currently under maintenance. Please check back later." }
            )
        },
        confirmButton = { }
    )
}
