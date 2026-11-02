package com.taptrack.app.ui.screens.tapstands

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.data.remote.optDoubleOrNull
import com.taptrack.app.data.remote.optStringOrNull
import com.taptrack.app.data.sync.SyncManager
import com.taptrack.app.data.sync.tapstandFromServer
import com.taptrack.app.ui.TsStatus
import com.taptrack.app.ui.components.CenterPinMapView
import com.taptrack.app.ui.distanceM
import com.taptrack.app.ui.theme.Web
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Relocation keeps the code only within this distance of where the tapstand was first coded. */
const val RELOCATE_LIMIT_M = 50.0

val RETIRED = setOf("inactive", "decommissioned")

private data class TsEvent(val kind: String, val from: String?, val to: String?, val dist: Double?, val reason: String?,
                           val related: String?, val by: String?, val at: String)
private data class TsRequest(val id: String, val kind: String, val reason: String, val dist: Double?, val status: String,
                             val requestedBy: String?, val by: String?, val at: String, val note: String?)

private val KIND_LABEL = mapOf("relocate" to "Relocation", "decommission" to "Decommission", "reinstate" to "Reinstatement")

/** A pending action waiting for the reason dialog. */
private sealed class Pending {
    data class Move(val lat: Double, val lng: Double, val fromAnchor: Double) : Pending()
    data object Decommission : Pending()
    data object Reinstate : Pending()
}

/**
 * Lifecycle of an OFFICIAL tapstand (online only): relocate (≤ 50 m keeps the code; farther = decommission +
 * new tapstand with a new code), decommission, reinstate (same code), history.
 * Office-level roles act; field sends requests the office approves on the web.
 */
@Composable
fun TapstandLifecycleSection(
    container: AppContainer,
    ts: TapstandEntity,
    meters: Int,
    codeOf: (String?) -> String,
    onOpenTapstand: (String) -> Unit,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val isOffice = container.session.isOffice
    val canRequest = container.session.canEdit
    val me = container.session.userId

    var tick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf<String?>(null) }
    var anchor by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var replacedBy by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<TsEvent>>(emptyList()) }
    var requests by remember { mutableStateOf<List<TsRequest>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }

    LaunchedEffect(ts.id, tick) {
        loading = true
        try {
            val row = container.api.select("tapstands?id=eq.${ts.id}&select=anchor_lat,anchor_lng,replaced_by,lat,lng").optJSONObject(0)
            anchor = row?.let { (it.optDoubleOrNull("anchor_lat") ?: it.optDouble("lat")) to (it.optDoubleOrNull("anchor_lng") ?: it.optDouble("lng")) }
            replacedBy = row?.optStringOrNull("replaced_by")
            events = container.api.select("tapstand_events?tapstand_id=eq.${ts.id}&select=*,profiles(full_name)&order=created_at.desc").mapObj { o ->
                TsEvent(o.getString("kind"), o.optStringOrNull("from_status"), o.optStringOrNull("to_status"), o.optDoubleOrNull("distance_m"),
                    o.optStringOrNull("reason"), o.optStringOrNull("related_id"), o.optJSONObject("profiles")?.optStringOrNull("full_name"),
                    o.getString("created_at"))
            }
            requests = container.api.select("tapstand_requests?tapstand_id=eq.${ts.id}&select=*,requester:profiles!tapstand_requests_requested_by_fkey(full_name)&order=requested_at.desc&limit=10").mapObj { o ->
                TsRequest(o.getString("id"), o.getString("kind"), o.getString("reason"), o.optDoubleOrNull("distance_m"), o.getString("status"),
                    o.optStringOrNull("requested_by"), o.optJSONObject("requester")?.optStringOrNull("full_name"), o.getString("requested_at"),
                    o.optStringOrNull("decision_note"))
            }
            offline = null
        } catch (e: Exception) {
            offline = e.message ?: "Offline"
        }
        loading = false
    }

    /** Runs an online action, then refreshes this tapstand (and the replacement, if any) on the phone. */
    fun act(done: String, block: suspend () -> String?) {
        scope.launch {
            busy = true; error = null; message = null
            try {
                val newId = block()
                container.api.select("tapstands?id=in.(${listOfNotNull(ts.id, newId).joinToString(",")})&select=${SyncManager.TAPSTAND_COLS}")
                    .mapObj { tapstandFromServer(it) }.forEach { container.db.tapstands().upsert(it) }
                container.sync.requestSync()
                message = done; tick++
                onChanged()
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            }
            busy = false
        }
    }

    val pendingKinds = requests.filter { it.status == "pending" }.map { it.kind }.toSet()
    val retired = ts.status in RETIRED

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tapstand lifecycle", style = MaterialTheme.typography.titleSmall)
        if (ts.status == "decommissioned") {
            Note("Decommissioned. Code ${ts.code} is retired and never reused." +
                (replacedBy?.let { " Replaced by ${codeOf(it)}." } ?: ""), Web.Slate100)
            replacedBy?.let { id -> TextButton(onClick = { onOpenTapstand(id) }) { Text("Open ${codeOf(id)}") } }
        }
        if (retired && meters > 0) Note("⚠ $meters meter(s) still assigned to this ${TsStatus.of(ts.status).label.lowercase()} tapstand — reassign them.", Web.Amber50)

        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            offline != null -> Hint("Go online to relocate, decommission, reinstate or see the history.")
            ts.dirty -> Hint("Sync this tapstand first (it has changes waiting to upload).")
            else -> {
                if (isOffice) {
                    if (ts.status != "decommissioned") OutlinedButton(enabled = !busy, onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("Relocate…") }
                    if (ts.status != "decommissioned") OutlinedButton(enabled = !busy, onClick = { pending = Pending.Decommission }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Decommission…") }
                    if (retired) OutlinedButton(enabled = !busy, onClick = { pending = Pending.Reinstate }, modifier = Modifier.fillMaxWidth()) { Text("Reinstate (same code)") }
                    Hint("Relocating keeps the code only within ${RELOCATE_LIMIT_M.toInt()} m of the original spot.")
                } else if (canRequest) {
                    if (ts.status != "decommissioned") OutlinedButton(enabled = !busy && "relocate" !in pendingKinds, onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if ("relocate" in pendingKinds) "Relocation requested" else "Request relocation…") }
                    if (ts.status != "decommissioned") OutlinedButton(enabled = !busy && "decommission" !in pendingKinds, onClick = { pending = Pending.Decommission }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(if ("decommission" in pendingKinds) "Decommission requested" else "Request decommission…") }
                    if (retired) OutlinedButton(enabled = !busy && "reinstate" !in pendingKinds, onClick = { pending = Pending.Reinstate }, modifier = Modifier.fillMaxWidth()) {
                        Text(if ("reinstate" in pendingKinds) "Reinstatement requested" else "Request reinstatement…") }
                    Hint("Moving, decommissioning or reinstating an official tapstand needs office approval.")
                }
            }
        }
        message?.let { Note(it, Web.Teal50) }
        error?.let { Note(it, Web.Rose50) }

        if (requests.isNotEmpty()) {
            Text("Requests", style = MaterialTheme.typography.labelLarge)
            requests.forEach { r ->
                Column(Modifier.fillMaxWidth()) {
                    Text(listOfNotNull(KIND_LABEL[r.kind] ?: r.kind, r.dist?.let { "${it.toInt()} m" }, r.status).joinToString(" · "),
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                        color = if (r.status == "pending") Web.Amber700 else MaterialTheme.colorScheme.onSurface)
                    Text("“${r.reason}” — ${r.by ?: "field"}, ${fmt(r.at)}" + (r.note?.let { " · office: $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = Web.Slate600)
                    if (r.status == "pending" && r.requestedBy == me && !isOffice) {
                        TextButton(enabled = !busy, onClick = {
                            act("Request withdrawn") { container.api.rpc("cancel_tapstand_request", JSONObject().put("p_id", r.id)); null }
                        }) { Text("Withdraw") }
                    }
                }
            }
            if (isOffice && "pending" in requests.map { it.status }) Hint("Approve or reject requests on the web app (Tapstands page).")
        }

        if (offline == null && !loading) {
            Text("History", style = MaterialTheme.typography.labelLarge)
            if (events.isEmpty()) Hint("No changes recorded yet.")
            events.forEach { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(eventText(e, codeOf) + (e.reason?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Text(fmt(e.at) + (e.by?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Web.Slate500)
                }
            }
        }
    }

    // ── Pick the new spot ──
    if (picking) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val sat = remember { ctx.getSharedPreferences("tt_map", android.content.Context.MODE_PRIVATE).getBoolean("satellite", false) }
        var pLat by remember { mutableDoubleStateOf(ts.lat) }
        var pLng by remember { mutableDoubleStateOf(ts.lng) }
        val a = anchor ?: (ts.lat to ts.lng)
        val d = distanceM(a.first, a.second, pLat, pLng)
        Dialog(onDismissRequest = { picking = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Box(Modifier.weight(1f)) {
                        CenterPinMapView(lat = pLat, lng = pLng, satellite = sat, modifier = Modifier.fillMaxSize(), onLocationPicked = { x, y -> pLat = x; pLng = y })
                    }
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("New spot: ${d.toInt()} m from where ${ts.code} was first coded", fontWeight = FontWeight.SemiBold,
                            color = if (d > RELOCATE_LIMIT_M) Web.Rose600 else Web.Teal700)
                        Text(if (d > RELOCATE_LIMIT_M) "Over ${RELOCATE_LIMIT_M.toInt()} m — it can't keep its code. " +
                                (if (isOffice) "It will be decommissioned and a new tapstand with a new code added here." else "The office decides; it may get a new code.")
                             else "Within ${RELOCATE_LIMIT_M.toInt()} m — keeps its code.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picking = false }, Modifier.weight(1f)) { Text("Cancel") }
                            Button(enabled = d >= 1, onClick = { picking = false; pending = Pending.Move(pLat, pLng, d) }, modifier = Modifier.weight(1f)) { Text("Next") }
                        }
                    }
                }
            }
        }
    }

    // ── Reason dialog ──
    pending?.let { p ->
        var reason by remember(p) { mutableStateOf("") }
        var moveMeters by remember(p) { mutableStateOf(true) }
        val far = p is Pending.Move && p.fromAnchor > RELOCATE_LIMIT_M
        val title = when (p) {
            is Pending.Move -> if (!isOffice) "Request relocation" else if (far) "Decommission & replace" else "Relocate ${ts.code}"
            Pending.Decommission -> if (isOffice) "Decommission ${ts.code}" else "Request decommission"
            Pending.Reinstate -> if (isOffice) "Reinstate ${ts.code}" else "Request reinstatement"
        }
        val needReason = !(isOffice && (p is Pending.Reinstate || (p is Pending.Move && !far)))
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when {
                        p is Pending.Move && far && isOffice -> "${p.fromAnchor.toInt()} m from the original spot. ${ts.code} will be decommissioned (code retired) and a NEW tapstand with a NEW code added at the new spot."
                        p is Pending.Move -> "${p.fromAnchor.toInt()} m from the original spot." + if (isOffice) " ${ts.code} keeps its code." else " Sent to the office for approval."
                        p is Pending.Decommission -> (if (isOffice) "Its code is retired and never reused." else "Sent to the office for approval.") +
                            if (meters > 0) " $meters meter(s) stay assigned and are flagged for reassignment." else ""
                        else -> if (isOffice) "It comes back with the same code ${ts.code}." else "Sent to the office for approval."
                    }, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(reason, { reason = it }, label = { Text(if (needReason) "Reason (required)" else "Reason (optional)") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                    if (isOffice && far && meters > 0) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(moveMeters, { moveMeters = it }); Text("Move the $meters meter(s) to the new tapstand", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !needReason || reason.isNotBlank(), onClick = {
                    pending = null
                    val why = reason.trim()
                    if (!isOffice) {
                        val row = JSONObject().put("tapstand_id", ts.id).put("project_id", ts.projectId).put("reason", why)
                        when (p) {
                            is Pending.Move -> { row.put("kind", "relocate").put("new_lat", p.lat).put("new_lng", p.lng) }
                            Pending.Decommission -> row.put("kind", "decommission")
                            Pending.Reinstate -> row.put("kind", "reinstate")
                        }
                        act("Request sent to the office") { container.api.insert("tapstand_requests", row); null }
                    } else when (p) {
                        is Pending.Move -> if (far) act("${ts.code} decommissioned — new tapstand added (code on sync)") {
                            container.api.rpc("replace_tapstand", JSONObject().put("p_tapstand_id", ts.id).put("p_lat", p.lat).put("p_lng", p.lng)
                                .put("p_reason", why).put("p_move_meters", moveMeters)).trim().trim('"')
                        } else act("${ts.code} moved ${p.fromAnchor.toInt()} m from the original spot") {
                            container.api.update("tapstands", ts.id, JSONObject().put("lat", p.lat).put("lng", p.lng).put("change_reason", why.ifEmpty { JSONObject.NULL })); null
                        }
                        Pending.Decommission -> act("${ts.code} decommissioned — code kept, never reused") {
                            container.api.update("tapstands", ts.id, JSONObject().put("status", "decommissioned").put("change_reason", why)); null
                        }
                        Pending.Reinstate -> act("${ts.code} reinstated with the same code") {
                            container.api.rpc("reinstate_tapstand", JSONObject().put("p_tapstand_id", ts.id).put("p_reason", why.ifEmpty { JSONObject.NULL })); null
                        }
                    }
                }) { Text(if (isOffice) "Confirm" else "Send request") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private fun <T> JSONArray.mapObj(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

private fun st(s: String?) = s?.let { TsStatus.of(it).label } ?: "—"

private fun eventText(e: TsEvent, codeOf: (String?) -> String): String {
    val m = e.dist?.let { "${it.toInt()} m" } ?: ""
    return when (e.kind) {
        "verified" -> "Made official (${st(e.to)})"
        "relocated" -> "Moved $m"
        "decommissioned" -> "Decommissioned (was ${st(e.from)})"
        "reinstated" -> "Reinstated → ${st(e.to)}"
        "replaced" -> "Replaced by ${codeOf(e.related)} — new spot $m from the original"
        "replaces" -> "Replaces ${codeOf(e.related)} ($m away)"
        else -> "Status ${st(e.from)} → ${st(e.to)}"
    }
}

private fun fmt(iso: String): String = runCatching {
    val inF = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val d = inF.parse(iso.take(19))!!
    SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(d)
}.getOrDefault(iso.take(16))

@Composable
private fun Note(text: String, bg: androidx.compose.ui.graphics.Color) {
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
