package com.taptrack.app.ui.screens.applicants

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.taptrack.app.ui.AppStatus
import com.taptrack.app.ui.fullName
import com.taptrack.app.ui.slotLabel

private enum class AppFilter(val label: String, val statuses: Set<String>?) {
    ALL("All", null),
    PENDING("To validate", setOf("submitted", "for_validation")),
    VALIDATED("Validated", setOf("validated")),
    APPROVED("Approved", setOf("approved", "connected")),
    OTHER("Waitlist / rejected", setOf("waitlisted", "rejected", "cancelled")),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicantListScreen(container: AppContainer, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val projectId = container.session.projectId ?: ""
    val applicants by remember { container.db.applicants().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val barangays by remember { container.db.barangays().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val tapstands by remember { container.db.tapstands().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val brgyName = remember(barangays) { barangays.associate { it.id to it.name } }
    val tsCode = remember(tapstands) { tapstands.associate { it.id to (it.code ?: "new tapstand") } }
    val tsRetired = remember(tapstands) { tapstands.filter { it.status in setOf("inactive", "decommissioned") }.map { it.id }.toSet() }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var brgyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var brgyMenu by remember { mutableStateOf(false) }

    val counts = remember(applicants) { AppFilter.entries.associateWith { f -> applicants.count { f.statuses?.contains(it.status) ?: true } } }
    val shown = remember(applicants, query, filter, brgyFilter) {
        val q = query.trim().lowercase()
        applicants.filter { a ->
            (filter.statuses?.contains(a.status) ?: true) && (brgyFilter == null || a.barangayId == brgyFilter) &&
                (q.isEmpty() || listOfNotNull(a.lastName, a.firstName, a.middleName, a.applicationNo, a.meterSerial, a.sitio, a.contactNo)
                    .any { it.lowercase().contains(q) })
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Applicants") }) },
        floatingActionButton = {
            if (container.session.canAdd) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Name, application no., meter no.…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ${counts[f] ?: 0}") })
                }
            }
            Box(Modifier.padding(horizontal = 12.dp)) {
                AssistChip(onClick = { brgyMenu = true }, label = { Text(brgyFilter?.let { brgyName[it] } ?: "All barangays") })
                DropdownMenu(expanded = brgyMenu, onDismissRequest = { brgyMenu = false }) {
                    DropdownMenuItem(text = { Text("All barangays") }, onClick = { brgyFilter = null; brgyMenu = false })
                    barangays.forEach { b -> DropdownMenuItem(text = { Text(b.name) }, onClick = { brgyFilter = b.id; brgyMenu = false }) }
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (applicants.isEmpty()) "No applicants yet.\nTap New to add the first one." else "Nothing matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.id }) { a ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(a.id) },
                            leadingContent = { Box(Modifier.size(12.dp).clip(CircleShape).background(AppStatus.color(a.status))) },
                            headlineContent = { Text(a.fullName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(
                                    a.applicationNo ?: "no. on sync",
                                    AppStatus.of(a.status).label,
                                    brgyName[a.barangayId],
                                    a.assignedTapstandId?.let { (if (it in tsRetired) "⚠ " else "") + slotLabel(tsCode[it], a.meterSlot) },
                                    a.meterSerial?.let { "meter $it" },
                                ).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                when {
                                    a.syncError != null -> Icon(Icons.Default.Warning, "Upload refused", tint = MaterialTheme.colorScheme.error)
                                    a.dirty -> Icon(Icons.Default.CloudUpload, "Waiting to upload", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
