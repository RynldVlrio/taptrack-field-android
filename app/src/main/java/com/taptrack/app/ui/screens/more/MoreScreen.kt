package com.taptrack.app.ui.screens.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taptrack.app.AppContainer
import com.taptrack.app.BuildConfig
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(container: AppContainer) {
    val s = container.session
    val sync by container.sync.state.collectAsStateWithLifecycle()
    val pendingTs by remember { container.db.tapstands().observePending() }.collectAsStateWithLifecycle(0)
    val pendingApp by remember { container.db.applicants().observePending() }.collectAsStateWithLifecycle(0)
    val pendingPhotos by remember { container.db.photos().observePending() }.collectAsStateWithLifecycle(0)
    val lastSync by remember { container.db.syncMeta().observe("last_sync") }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    val pending = pendingTs + pendingApp + pendingPhotos

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.email ?: "", style = MaterialTheme.typography.bodySmall)
                    Text("${s.projectName}  ·  role: ${s.role}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val last = lastSync?.toLongOrNull()?.let { SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(it)) } ?: "never"
                    Text("Last synced: $last", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (pending == 0) "Everything on this phone is uploaded."
                        else "Waiting to upload: $pendingTs tapstand(s), $pendingApp applicant(s), $pendingPhotos photo(s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    sync.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { scope.launch { container.sync.syncNow() } }, enabled = !sync.running) {
                        if (sync.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Sync, null)
                        Spacer(Modifier.width(8.dp)); Text(if (sync.running) "Syncing…" else "Sync now")
                    }
                    Text("Sync also runs automatically every minute and after each save.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
            Text("TapTrack Field ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(if (pending > 0) "⚠ $pending change(s) on this phone are NOT uploaded yet and will be lost. Sync first while online."
                     else "This phone's copy of the data will be removed. It downloads again when you sign in.")
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { container.signOut() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = if (pending > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                    Text(if (pending > 0) "Sign out anyway" else "Sign out")
                }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
