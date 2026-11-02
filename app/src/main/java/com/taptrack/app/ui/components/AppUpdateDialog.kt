package com.taptrack.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taptrack.app.ui.theme.Web
import com.taptrack.app.utils.AppUpdater
import com.taptrack.app.utils.ReleaseInfo
import com.taptrack.app.utils.UpdateState
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

/** Update / maintenance dialogs, shown over the whole app. */
@Composable
fun AppUpdateDialog(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // re-evaluate "allow installs" after returning from Android settings
    var permTick by remember { mutableIntStateOf(0) }

    when (val s = state) {
        is UpdateState.Maintenance -> {
            BackHandler(enabled = true) {}
            AlertDialog(
                onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Under maintenance") },
                text = { Text(s.message.ifBlank { "TapTrack is under maintenance. Please try again later." }) },
                confirmButton = { TextButton(onClick = { updater.recheck() }) { Text("Check again") } },
            )
        }
        is UpdateState.Available -> UpdateBox(s.info, updater, onDismiss = { updater.later() }) {
            Button(onClick = { updater.download(s.info) }) { Text("Update now") }
        }
        is UpdateState.Downloading -> UpdateBox(s.info, updater, onDismiss = {}) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val total = if (s.total > 0) s.total else s.info.sizeBytes
                if (total > 0) LinearProgressIndicator(progress = (s.done.toFloat() / total).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Downloading ${mb(s.done)}" + if (total > 0) " of ${mb(total)}" else "", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { updater.cancelDownload() }) { Text("Cancel") }
            }
        }
        is UpdateState.ReadyToInstall -> {
            val needPerm = remember(permTick) { updater.needsInstallPermission() }
            UpdateBox(s.info, updater, onDismiss = { updater.later() }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (needPerm) {
                        Text("First allow TapTrack to install updates (one time): tap the button, switch on \"Allow from this source\", then come back.",
                            style = MaterialTheme.typography.bodySmall, color = Web.Amber700)
                        Button(onClick = { updater.openInstallPermissionSettings(context); permTick++ }) { Text("Allow installs") }
                        TextButton(onClick = { permTick++ }) { Text("I allowed it — continue") }
                    } else {
                        Button(onClick = { updater.install(context, s.file) }) { Text("Install update") }
                    }
                }
            }
        }
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { updater.later() },
            title = { Text("Update") },
            text = { Text(s.message) },
            confirmButton = { if (s.info != null) TextButton(onClick = { updater.download(s.info) }) { Text("Try again") } },
            dismissButton = { if (s.info?.required != true) TextButton(onClick = { updater.later() }) { Text("Close") } },
        )
        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { updater.later() },
            title = { Text("You're up to date") },
            text = { Text("TapTrack Field ${updater.currentName} is the latest version.") },
            confirmButton = { TextButton(onClick = { updater.later() }) { Text("OK") } },
        )
        else -> {}
    }
}

@Composable
private fun UpdateBox(info: ReleaseInfo, updater: AppUpdater, onDismiss: () -> Unit, actions: @Composable () -> Unit) {
    if (info.required) BackHandler(enabled = true) {}
    AlertDialog(
        onDismissRequest = { if (!info.required) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !info.required, dismissOnClickOutside = !info.required),
        title = { Text(if (info.required) "Update required" else "Update available") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TapTrack Field ${info.versionName}", fontWeight = FontWeight.SemiBold)
                Text("You have ${updater.currentName}" + if (info.sizeBytes > 0) " · download ${mb(info.sizeBytes)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.required) Text("This version must be installed to keep using the app. Your saved data stays on the phone.",
                    style = MaterialTheme.typography.bodySmall, color = Web.Amber700)
                else info.daysLeft?.let { d -> Text("It becomes required in $d day(s).", style = MaterialTheme.typography.bodySmall, color = Web.Slate500) }
                if (info.notes.isNotBlank()) {
                    Text("What's new", style = MaterialTheme.typography.labelLarge)
                    Text(info.notes, style = MaterialTheme.typography.bodySmall)
                }
                actions()
            }
        },
        confirmButton = {},
        dismissButton = { if (!info.required) TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
