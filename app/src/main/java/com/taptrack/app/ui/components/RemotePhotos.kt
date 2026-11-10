package com.taptrack.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.taptrack.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A photo of a record that is on the server (taken on any phone). */
data class RemotePhoto(val id: String, val kind: String, val takenAt: String?, val file: File)

/**
 * Photos of a record stored on the server (e.g. the installation photo taken on another phone).
 * Downloaded once into the app cache (needs internet to list them).
 * [skipIds] = photos already shown from this phone's own copy.
 */
@Composable
fun RemotePhotos(
    container: AppContainer,
    entityType: String,
    entityId: String,
    skipIds: Set<String> = emptySet(),
    size: Int = 96,
    showEmpty: Boolean = false,
    replacing: Boolean = false,      // Surveyed → Installed in progress: these get replaced by the installation photo
) {
    val context = LocalContext.current
    var photos by remember(entityId) { mutableStateOf<List<RemotePhoto>?>(null) }
    var offline by remember(entityId) { mutableStateOf(false) }
    var big by remember { mutableStateOf<RemotePhoto?>(null) }

    LaunchedEffect(entityType, entityId) {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        photos = withContext(Dispatchers.IO) {
            try {
                val arr = container.api.select(
                    "attachments?select=id,kind,storage_path,taken_at&entity_type=eq.$entityType&entity_id=eq.$entityId" +
                        "&deleted_at=is.null&order=taken_at.desc"
                )
                var storeUrl: String? = null
                (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
                    val id = o.getString("id")
                    val f = File(dir, "$id.jpg")
                    val path = o.getString("storage_path")
                    if (!f.exists()) runCatching {
                        if (path.startsWith("gdrive:")) {
                            // kept in Google Drive through the TapTrack Photo Store (Settings → Photo storage on the web)
                            val url = storeUrl ?: container.api.selectAnon("app_config?select=value&key=eq.photoStoreUrl")
                                .optJSONObject(0)?.optString("value")?.trim().orEmpty().also { storeUrl = it }
                            if (url.startsWith("https://")) {
                                val r = container.api.photoStore(url, org.json.JSONObject()
                                    .put("action", "get").put("project", container.session.projectId ?: "")
                                    .put("fileId", path.removePrefix("gdrive:").substringBefore("|")))
                                f.writeBytes(android.util.Base64.decode(r.getString("data"), android.util.Base64.DEFAULT))
                            }
                        } else f.writeBytes(container.api.downloadFile("media", path))
                    }
                    if (f.exists()) RemotePhoto(id, o.optString("kind", "photo"), o.optString("taken_at").takeIf { it.isNotBlank() && it != "null" }, f) else null
                }.sortedByDescending { it.kind == "installation" }
            } catch (e: Exception) {
                offline = true
                emptyList()
            }
        }
    }

    val shown = photos?.filter { it.id !in skipIds }.orEmpty()
    if (photos == null) {
        Text("Loading photos…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (shown.isEmpty()) {
        if (showEmpty || offline) Text(
            if (offline) "Server photos need internet." else "No photos on the server yet.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { p -> PhotoTile(p, size, replacing && p.kind != "installation") { big = p } }
    }

    big?.let { p ->
        Dialog(onDismissRequest = { big = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { big = null }, contentAlignment = Alignment.Center) {
                AsyncImage(model = p.file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                Text(label(p), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }
        }
    }
}

@Composable
private fun PhotoTile(p: RemotePhoto, size: Int, replaced: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).alpha(if (replaced) 0.4f else 1f).clickable(onClick = onClick)) {
        AsyncImage(model = p.file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Text(
            if (replaced) "Will be replaced" else label(p), style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(if (p.kind == "installation") Color(0xD94D7C0F) else Color(0x99000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private fun label(p: RemotePhoto): String =
    (if (p.kind == "installation") "Installation" else "Photo") + (p.takenAt?.take(10)?.let { " · $it" } ?: "")
