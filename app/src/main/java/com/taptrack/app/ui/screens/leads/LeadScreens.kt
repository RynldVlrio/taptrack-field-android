package com.taptrack.app.ui.screens.leads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.ApplicantEntity
import com.taptrack.app.data.local.LeadEntity
import com.taptrack.app.ui.AppStatus
import com.taptrack.app.ui.fullName
import com.taptrack.app.ui.theme.Web

// ─────────────────────────────────────────────────────────────────────────────
// HH Survey leads (view-only on phones). Leads come from the survey CSV uploaded
// on the web; field staff look them up, visit, and start an application.
// ─────────────────────────────────────────────────────────────────────────────

/** Willingness to connect, grouped from the survey's free text. */
enum class LeadWill(val label: String, val argb: Int) {
    YES("Willing", 0xFF16A34A.toInt()),
    MAYBE("Undecided", 0xFFD97706.toInt()),
    NO("Not willing", 0xFFDC2626.toInt()),
    UNKNOWN("No answer", 0xFF64748B.toInt());

    companion object {
        fun of(text: String?): LeadWill {
            val t = text?.trim()?.lowercase().orEmpty()
            return when {
                t.isEmpty() -> UNKNOWN
                t.startsWith("no") || t.contains("not ") || t.contains("unwilling") || t.contains("ayaw") -> NO
                t.startsWith("yes") || t.startsWith("oo") || t.contains("willing") || t == "y" -> YES
                else -> MAYBE   // maybe / undecided / later / depends …
            }
        }
    }
}

val LeadEntity.will: LeadWill get() = LeadWill.of(willingness)
val LeadEntity.displayName: String get() = "$firstName $lastName".trim().ifEmpty { "(no name)" }

/** Applicants that came from this lead (same survey record ID). */
fun applicantsFor(lead: LeadEntity, applicants: List<ApplicantEntity>): List<ApplicantEntity> =
    if (lead.recordUuid == null) emptyList()
    else applicants.filter { !it.deleted && it.sourceLeadUuid == lead.recordUuid && it.status != "cancelled" }

// ── List ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeadListScreen(container: AppContainer, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val projectId = container.session.projectId ?: ""
    val leads by remember { container.db.leads().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val applicants by remember { container.db.applicants().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val linked = remember(applicants) { applicants.filter { !it.deleted && it.status != "cancelled" }.mapNotNull { it.sourceLeadUuid }.toSet() }

    var query by rememberSaveable { mutableStateOf("") }
    var will by rememberSaveable { mutableStateOf<LeadWill?>(null) }
    var brgy by rememberSaveable { mutableStateOf<String?>(null) }
    var brgyMenu by remember { mutableStateOf(false) }
    val brgyNames = remember(leads) { leads.mapNotNull { it.barangayName?.trim()?.ifEmpty { null } }.distinct().sorted() }

    val shown = remember(leads, query, will, brgy) {
        val q = query.trim().lowercase()
        leads.filter { l ->
            (will == null || l.will == will) && (brgy == null || l.barangayName?.trim() == brgy) &&
                (q.isEmpty() || listOfNotNull(l.firstName, l.lastName, l.zone, l.contactNo).any { it.lowercase().contains(q) })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HH Survey leads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Name, zone, contact no.…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(onClick = { brgyMenu = true }, label = { Text(brgy ?: "All barangays") })
                    DropdownMenu(expanded = brgyMenu, onDismissRequest = { brgyMenu = false }) {
                        DropdownMenuItem(text = { Text("All barangays") }, onClick = { brgy = null; brgyMenu = false })
                        brgyNames.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { brgy = n; brgyMenu = false }) }
                    }
                }
                FilterChip(selected = will == null, onClick = { will = null }, label = { Text("All ${leads.size}") })
                LeadWill.entries.forEach { w ->
                    val n = leads.count { it.will == w }
                    if (n > 0) FilterChip(selected = will == w, onClick = { will = if (will == w) null else w },
                        leadingIcon = { Dot(w.argb) }, label = { Text("${w.label} $n") })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (leads.isEmpty()) "No survey leads on this phone yet.\nThey download with sync once the office uploads the survey CSV."
                        else "Nothing matches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }) { l ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(l.id) },
                            leadingContent = { Dot(l.will.argb, 12) },
                            headlineContent = { Text(l.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(l.barangayName, l.zone, l.will.label, if (l.lat == null) "no GPS" else null)
                                    .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = { if (l.recordUuid != null && l.recordUuid in linked) AppliedChip() },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ── Detail ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LeadDetailScreen(
    container: AppContainer,
    leadId: String,
    onBack: () -> Unit,
    onStartApplication: (String) -> Unit,
    onOpenApplicant: (String) -> Unit,
) {
    val context = LocalContextCompat()
    val projectId = container.session.projectId ?: ""
    var lead by remember { mutableStateOf<LeadEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(leadId) { lead = container.db.leads().get(leadId); loaded = true }
    val applicants by remember { container.db.applicants().observe(projectId) }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Survey lead") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        val l = lead
        if (!loaded) { Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        if (l == null) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("This lead is no longer on this phone (the survey list may have been replaced). Go back and open it again.")
            }
            return@Scaffold
        }
        val linked = applicantsFor(l, applicants)
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(l.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(l.will.argb, 12); Spacer(Modifier.width(6.dp))
                Text(l.will.label + (l.willingness?.takeIf { it.isNotBlank() && !it.equals(l.will.label, true) }?.let { "  ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
            }

            if (linked.isNotEmpty()) {
                Surface(color = Web.Teal50, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Already applied", fontWeight = FontWeight.SemiBold, color = Web.Teal800)
                        linked.forEach { a ->
                            Text("• ${a.fullName} — ${a.applicationNo ?: "no. on sync"}, ${AppStatus.of(a.status).label}",
                                style = MaterialTheme.typography.bodySmall, color = Web.Teal800,
                                modifier = Modifier.clickable { onOpenApplicant(a.id) })
                        }
                    }
                }
            }

            // ── actions ──
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (l.lat != null && l.lng != null) OutlinedButton(onClick = { navigate(context, l.lat, l.lng) }) {
                    Icon(Icons.Default.Navigation, null); Spacer(Modifier.width(6.dp)); Text("Navigate")
                }
                l.contactNo?.takeIf { it.isNotBlank() }?.let { no ->
                    OutlinedButton(onClick = { dial(context, no) }) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text("Call") }
                }
                l.photoLink?.takeIf { it.startsWith("http") }?.let { url ->
                    OutlinedButton(onClick = { openUrl(context, url) }) { Icon(Icons.Default.Photo, null); Spacer(Modifier.width(6.dp)); Text("Photo") }
                }
            }
            if (container.session.canAdd) {
                Button(onClick = { onStartApplication(l.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp))
                    Text(if (linked.isEmpty()) "Start application" else "Start another application (same household)")
                }
                Text("Name, contact, barangay and GPS are copied in — you can change them (e.g. another household member).",
                    style = MaterialTheme.typography.labelSmall, color = Web.Slate500)
            }

            // ── details ──
            Info("Household") {
                Line("Barangay", l.barangayName); Line("Zone / sitio", l.zone); Line("Contact no.", l.contactNo)
                Line("Household members", l.householdMembers?.toString()); Line("Ownership", l.householdOwnership)
            }
            Info("Water") {
                Line("Existing water source", l.existingWaterSource); Line("Drinking water source", l.drinkingWaterSource)
                Line("Willingness to connect", l.willingness)
            }
            Info("Survey") {
                Line("Survey date", l.surveyDate)
                Line("GPS", if (l.lat != null && l.lng != null) "%.6f, %.6f".format(java.util.Locale.US, l.lat, l.lng) else "not recorded")
                Line("Remarks", l.remarks)
            }
        }
    }
}

// ── small pieces ─────────────────────────────────────────────────────────────

@Composable private fun LocalContextCompat(): Context = androidx.compose.ui.platform.LocalContext.current

@Composable
private fun Dot(argb: Int, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(argb)))
}

@Composable
fun AppliedChip() {
    Text("Applied", style = MaterialTheme.typography.labelSmall, color = Web.Teal800,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Web.Teal50).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun Info(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String?) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Web.Slate500, modifier = Modifier.weight(0.45f))
        Text(value?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.55f))
    }
}

private fun navigate(context: Context, lat: Double, lng: Double) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng"))) } catch (_: Exception) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) }
        catch (_: Exception) { Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show() }
    }
}

private fun dial(context: Context, number: String) {
    try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))) }
    catch (_: Exception) { Toast.makeText(context, "Can't open the phone dialer", Toast.LENGTH_SHORT).show() }
}

private fun openUrl(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch (_: Exception) { Toast.makeText(context, "Can't open the link", Toast.LENGTH_SHORT).show() }
}
