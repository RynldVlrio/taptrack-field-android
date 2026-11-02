package com.taptrack.app.ui.screens.tapstands

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.BarangayEntity
import com.taptrack.app.ui.TsKind
import com.taptrack.app.ui.TsStatus
import com.taptrack.app.ui.kind
import com.taptrack.app.ui.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapstandListScreen(
    container: AppContainer,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val projectId = container.session.projectId ?: ""
    val tapstands by remember { container.db.tapstands().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val barangays by remember { container.db.barangays().observe(projectId) }.collectAsStateWithLifecycle(emptyList<BarangayEntity>())
    val brgyName = remember(barangays) { barangays.associate { it.id to it.name } }

    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(TsKind.OFFICIAL) }
    var brgyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var brgyMenu by remember { mutableStateOf(false) }

    val counts = remember(tapstands) { tapstands.groupingBy { it.kind }.eachCount() }
    val shown = remember(tapstands, query, kind, brgyFilter) {
        val q = query.trim().lowercase()
        tapstands.filter { t ->
            t.kind == kind && (brgyFilter == null || t.barangayId == brgyFilter) &&
                (q.isEmpty() || listOfNotNull(t.code, t.draftLabel, t.sitio, t.locationDesc, brgyName[t.barangayId])
                    .any { it.lowercase().contains(q) })
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tapstands") }) },
        floatingActionButton = {
            if (container.session.canEdit) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search code, sitio, barangay…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(TsKind.OFFICIAL to "Official", TsKind.PROPOSED to "Proposed", TsKind.CANCELLED to "Cancelled").forEach { (k, l) ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text("$l ${counts[k] ?: 0}") })
                }
            }
            Box(Modifier.padding(horizontal = 12.dp)) {
                AssistChip(onClick = { brgyMenu = true }, label = { Text(brgyFilter?.let { brgyName[it] } ?: "All barangays") })
                DropdownMenu(expanded = brgyMenu, onDismissRequest = { brgyMenu = false }) {
                    DropdownMenuItem(text = { Text("All barangays") }, onClick = { brgyFilter = null; brgyMenu = false })
                    barangays.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name + (b.code?.let { " ($it)" } ?: "")) }, onClick = { brgyFilter = b.id; brgyMenu = false })
                    }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (tapstands.isEmpty()) "No tapstands yet.\nSync from the More tab if you expected some." else "Nothing matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.id }) { t ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(t.id) },
                            leadingContent = { Box(Modifier.size(14.dp).clip(CircleShape).background(TsStatus.color(t.status))) },
                            headlineContent = { Text(t.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(TsStatus.of(t.status).label, brgyName[t.barangayId], t.sitio).joinToString(" · "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                when {
                                    t.syncError != null -> Icon(Icons.Default.Warning, "Upload refused", tint = MaterialTheme.colorScheme.error)
                                    t.dirty -> Icon(Icons.Default.CloudUpload, "Waiting to upload", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
