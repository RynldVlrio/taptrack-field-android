package com.taptrack.app.ui.screens.applicants

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.taptrack.app.AppContainer
import com.taptrack.app.data.local.ApplicantEntity
import com.taptrack.app.data.local.PhotoEntity
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.ui.AppStatus
import com.taptrack.app.ui.FIELD_STATUSES
import com.taptrack.app.ui.TsKind
import com.taptrack.app.ui.TsStatus
import com.taptrack.app.ui.distanceM
import com.taptrack.app.ui.fullName
import com.taptrack.app.ui.holdsSlot
import com.taptrack.app.ui.kind
import com.taptrack.app.ui.label
import com.taptrack.app.ui.screens.map.StatusBadge
import com.taptrack.app.ui.theme.Web
import com.taptrack.app.ui.components.BarcodeScanner
import com.taptrack.app.ui.components.CameraCapture
import com.taptrack.app.ui.components.CenterPinMapView
import com.taptrack.app.ui.components.PROJECT_LAT
import com.taptrack.app.ui.components.PROJECT_LNG
import com.taptrack.app.utils.getLastKnownLocation
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val ACCOUNT_TYPES = listOf("residential", "commercial", "institutional", "government")
private val LOCKED = setOf("approved", "connected")   // assignment changes after approval are for the office

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class, ExperimentalLayoutApi::class)
@Composable
fun ApplicantEditScreen(
    container: AppContainer,
    applicantId: String?,
    initialLat: Double?,
    initialLng: Double?,
    initialTapstandId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectId = container.session.projectId ?: ""
    val canEdit = container.session.canEdit
    val isOffice = container.session.isOffice
    val barangays by remember { container.db.barangays().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val tapstands by remember { container.db.tapstands().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val applicants by remember { container.db.applicants().observe(projectId) }.collectAsStateWithLifecycle(emptyList())

    val isNew = applicantId == null
    val newId = remember { UUID.randomUUID().toString() }
    val entityId = applicantId ?: newId
    var original by remember { mutableStateOf<ApplicantEntity?>(null) }
    var loaded by remember { mutableStateOf(isNew) }

    // ── Form state ───────────────────────────────────────────────────────────
    var lastName by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var middleName by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var contactNo by remember { mutableStateOf("") }
    var accountType by remember { mutableStateOf("residential") }
    var household by remember { mutableStateOf("") }
    var dateApplied by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var barangayId by remember { mutableStateOf<String?>(null) }
    var sitio by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf(initialLat?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var lngText by remember { mutableStateOf(initialLng?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var status by remember { mutableStateOf("submitted") }
    var tsId by remember { mutableStateOf(initialTapstandId) }
    var slot by remember { mutableStateOf<Int?>(null) }          // null = next free (server picks)
    var serial by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }

    LaunchedEffect(applicantId) {
        if (applicantId != null) {
            container.db.applicants().get(applicantId)?.let { a ->
                original = a
                lastName = a.lastName; firstName = a.firstName; middleName = a.middleName ?: ""; suffix = a.suffix ?: ""
                contactNo = a.contactNo ?: ""; accountType = a.accountType; household = a.householdSize?.toString() ?: ""
                dateApplied = a.dateApplied.ifBlank { dateApplied }; barangayId = a.barangayId; sitio = a.sitio ?: ""; address = a.address ?: ""
                latText = a.lat?.let { "%.6f".format(Locale.US, it) } ?: ""; lngText = a.lng?.let { "%.6f".format(Locale.US, it) } ?: ""
                status = a.status; tsId = a.assignedTapstandId; slot = a.meterSlot; serial = a.meterSerial ?: ""; remarks = a.remarks ?: ""
            }
            loaded = true
        }
    }
    // New applicant from a tapstand: take that tapstand's barangay
    LaunchedEffect(initialTapstandId, tapstands) {
        if (isNew && initialTapstandId != null && barangayId == null) {
            tapstands.firstOrNull { it.id == initialTapstandId }?.let { barangayId = it.barangayId }
        }
    }

    val locked = (original?.status ?: status) in LOCKED && !isOffice
    val editable = canEdit
    val lat = latText.toDoubleOrNull()
    val lng = lngText.toDoubleOrNull()
    val brgy = barangays.firstOrNull { it.id == barangayId }

    // ── Tapstand options: official, active, same barangay; nearest first ─────
    val others = remember(applicants, entityId) { applicants.filter { it.id != entityId && it.holdsSlot } }
    val usedBy = remember(others) { others.filter { it.assignedTapstandId != null }.groupBy { it.assignedTapstandId!! } }
    val options = remember(tapstands, barangayId, lat, lng, tsId) {
        tapstands.filter {
            (it.kind == TsKind.OFFICIAL && it.status !in setOf("inactive", "decommissioned") && barangayId != null && it.barangayId == barangayId) || it.id == tsId
        }.sortedWith(compareBy<TapstandEntity> { if (lat != null && lng != null) distanceM(lat, lng, it.lat, it.lng) else 0.0 }.thenBy { it.code ?: "" })
    }
    val selTs = tapstands.firstOrNull { it.id == tsId }
    val selUsed = selTs?.let { usedBy[it.id].orEmpty() } ?: emptyList()
    val overCap = selTs != null && selUsed.size >= selTs.maxConnections && original?.assignedTapstandId != selTs.id

    // ── Duplicates (checked on this phone's copy of the data) ────────────────
    val dupes = remember(applicants, lastName, firstName) {
        val ln = lastName.trim().uppercase(); val fn = firstName.trim().uppercase()
        if (ln.length < 2 || fn.length < 2) emptyList()
        else applicants.filter { it.id != entityId && !it.deleted && it.lastName.uppercase() == ln && it.firstName.uppercase().startsWith(fn) }.take(3)
    }
    val serialNorm = serial.trim().uppercase()
    val serialDupe = remember(applicants, serialNorm) {
        if (serialNorm.isEmpty()) null else applicants.firstOrNull { it.id != entityId && it.holdsSlot && it.meterSerial?.uppercase() == serialNorm }
    }

    val savedPhotos by remember(entityId) { container.db.photos().observeFor("applicant", entityId) }.collectAsStateWithLifecycle(emptyList())
    val newPhotos = remember { mutableStateListOf<String>() }

    var showCamera by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var confirmOver by remember { mutableStateOf(false) }
    var brgyMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var gpsBusy by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var pendingCamera by remember { mutableStateOf<String?>(null) }   // "photo" / "scan" waiting for permission
    LaunchedEffect(cameraPermission.status.isGranted) {
        if (cameraPermission.status.isGranted) when (pendingCamera) { "photo" -> showCamera = true; "scan" -> showScanner = true }
        pendingCamera = null
    }
    fun withCamera(what: String) {
        if (cameraPermission.status.isGranted) { if (what == "photo") showCamera = true else showScanner = true }
        else { pendingCamera = what; cameraPermission.launchPermissionRequest() }
    }

    fun save(force: Boolean = false) {
        if (lastName.isBlank() || firstName.isBlank()) { toast(context, "Last name and first name are required"); return }
        if ((lat == null) != (lng == null) || (latText.isNotBlank() && lat == null) || (lngText.isNotBlank() && lng == null)) {
            toast(context, "Enter both latitude and longitude, or neither"); return
        }
        if (tsId != null && barangayId == null) { toast(context, "Choose the barangay first"); return }
        if (serialDupe != null) { toast(context, "Meter no. $serialNorm is already used by ${serialDupe.fullName}"); return }
        if (overCap && !force) { confirmOver = true; return }
        val base = original ?: ApplicantEntity(
            id = newId, projectId = projectId, applicationNo = null, lastName = "", firstName = "", middleName = null, suffix = null,
            contactNo = null, barangayId = null, sitio = null, address = null, householdSize = null, lat = null, lng = null,
            dateApplied = dateApplied, assignedTapstandId = null, meterSlot = null, meterSerial = null, decisionReason = null,
            remarks = null, isNew = true,
        )
        val row = base.copy(
            lastName = lastName.trim().uppercase(), firstName = firstName.trim().uppercase(),
            middleName = middleName.trim().uppercase().ifEmpty { null }, suffix = suffix.trim().uppercase().ifEmpty { null },
            contactNo = contactNo.trim().ifEmpty { null }, accountType = accountType, householdSize = household.toIntOrNull(),
            dateApplied = dateApplied, barangayId = barangayId, sitio = sitio.trim().ifEmpty { null }, address = address.trim().ifEmpty { null },
            lat = lat, lng = lng, status = status,
            assignedTapstandId = tsId, meterSlot = if (tsId == null) null else slot, meterSerial = serialNorm.ifEmpty { null },
            remarks = remarks.trim().ifEmpty { null },
            dirty = true, syncError = null, localUpdatedAt = System.currentTimeMillis(),
        )
        scope.launch {
            container.db.applicants().upsert(row)
            newPhotos.forEach { path ->
                container.db.photos().insert(PhotoEntity(UUID.randomUUID().toString(), projectId, "applicant", row.id, filePath = path, lat = lat, lng = lng))
            }
            container.sync.requestSync()
            toast(context, if (isNew) "Saved — application no. is assigned when it syncs" else "Saved")
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New applicant" else original?.applicationNo ?: "Applicant") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (!loaded) { Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        if (!isNew && original == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Applicant not found on this phone.") }; return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            original?.syncError?.let { Banner("⚠ The server refused the last change: $it", error = true) }
            original?.decisionReason?.takeIf { it.isNotBlank() }?.let { Banner("Office note: $it") }

            // ── Applicant ──
            Section("Applicant")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(lastName, { lastName = it }, "Last name *", editable, Modifier.weight(1f), caps = true)
                Field(firstName, { firstName = it }, "First name *", editable, Modifier.weight(1f), caps = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(middleName, { middleName = it }, "Middle name", editable, Modifier.weight(1f), caps = true)
                Field(suffix, { suffix = it }, "Suffix", editable, Modifier.weight(0.6f), caps = true)
            }
            if (dupes.isNotEmpty()) {
                Banner("⚠ Possible duplicate: " + dupes.joinToString("; ") { "${it.fullName} (${it.applicationNo ?: "pending"}, ${AppStatus.of(it.status).label})" }, warn = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(contactNo, { contactNo = it }, "Contact no.", editable, Modifier.weight(1f), type = KeyboardType.Phone)
                Field(household, { household = it.filter(Char::isDigit) }, "Household", editable, Modifier.weight(0.7f), type = KeyboardType.Number)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = typeMenu && editable, onExpandedChange = { typeMenu = it }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(accountType.replaceFirstChar { it.uppercase() }, {}, readOnly = true, enabled = editable, label = { Text("Account type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = typeMenu && editable, onDismissRequest = { typeMenu = false }) {
                        ACCOUNT_TYPES.forEach { t -> DropdownMenuItem(text = { Text(t.replaceFirstChar { it.uppercase() }) }, onClick = { accountType = t; typeMenu = false }) }
                    }
                }
                OutlinedTextField(dateApplied, {}, readOnly = true, enabled = editable, label = { Text("Date applied") },
                    trailingIcon = { IconButton(onClick = { if (editable) showDate = true }) { Icon(Icons.Default.CalendarMonth, "Pick date") } },
                    modifier = Modifier.weight(1f))
            }

            // ── Location ──
            Section("House location")
            ExposedDropdownMenuBox(expanded = brgyMenu && editable && !locked, onExpandedChange = { brgyMenu = it }) {
                OutlinedTextField(brgy?.name ?: "", {}, readOnly = true, enabled = editable && !locked, label = { Text("Barangay") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(brgyMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = brgyMenu && editable && !locked, onDismissRequest = { brgyMenu = false }) {
                    barangays.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name) }, onClick = {
                            barangayId = b.id; brgyMenu = false
                            if (selTs != null && selTs.barangayId != b.id) { tsId = null; slot = null; toast(context, "Tapstand cleared — it is in another barangay") }
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(sitio, { sitio = it }, "Sitio / purok", editable, Modifier.weight(1f))
                Field(address, { address = it }, "Address / landmark", editable, Modifier.weight(1.4f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(latText, { latText = it }, "Latitude", editable, Modifier.weight(1f), type = KeyboardType.Decimal)
                Field(lngText, { lngText = it }, "Longitude", editable, Modifier.weight(1f), type = KeyboardType.Decimal)
            }
            if (editable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !gpsBusy, onClick = {
                    scope.launch {
                        gpsBusy = true
                        val loc = context.getLastKnownLocation()
                        if (loc != null) {
                            latText = "%.6f".format(Locale.US, loc.latitude); lngText = "%.6f".format(Locale.US, loc.longitude)
                            toast(context, "GPS accuracy ±${loc.accuracy.toInt()} m")
                        } else toast(context, "No GPS fix yet — try again outdoors")
                        gpsBusy = false
                    }
                }) { Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Use GPS") }
                OutlinedButton(onClick = { showPicker = true }) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(6.dp)); Text("Pick on map") }
            }

            // ── Tapstand & meter ──
            Section("Tapstand & water meter")
            Hint("Only tapstands in the applicant's barangay. Design capacity is 10 meters. Tapstand, slot and meter no. are all needed before the office can approve.")
            if (locked) Banner("${AppStatus.of(original!!.status).label} — only the office can change the tapstand or meter.")
            when {
                barangayId == null -> Hint("Choose the barangay first.")
                options.isEmpty() -> Hint("No official tapstands in ${brgy?.name ?: "this barangay"} yet.")
                else -> Column(Modifier.border(1.dp, Web.Slate200, RoundedCornerShape(8.dp))) {
                    if (tsId != null && editable && !locked) {
                        TextButton(onClick = { tsId = null; slot = null }) { Text("Clear tapstand") }
                    }
                    options.forEach { t ->
                        val used = usedBy[t.id]?.size ?: 0
                        val selected = t.id == tsId
                        val full = used >= t.maxConnections
                        Row(
                            Modifier.fillMaxWidth().background(if (selected) Web.Teal50 else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(enabled = editable && !locked) { if (!selected) { tsId = t.id; slot = null } }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = editable && !locked)
                            Spacer(Modifier.width(6.dp))
                            StatusBadge(t.status, 18f)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(TsStatus.of(t.status).label, t.sitio,
                                    if (lat != null && lng != null) "${distanceM(lat, lng, t.lat, t.lng).toInt()} m away" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = Web.Slate500)
                            }
                            Text("$used/${t.maxConnections}", style = MaterialTheme.typography.labelMedium,
                                color = if (full) Web.Amber700 else Web.Slate600, fontWeight = if (full) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            if (selTs != null) {
                val taken = selUsed.filter { it.meterSlot != null }.associateBy { it.meterSlot!! }
                val n = maxOf(selTs.maxConnections, (taken.keys.maxOrNull() ?: 0), selUsed.size + 1)
                if (overCap) Banner("${selTs.label} already has ${selUsed.size} of ${selTs.maxConnections} meters (design capacity). You can still add this one if the team decides so.", warn = true)
                Text("Meter slot on ${selTs.label}", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlotChip("Auto", selected = slot == null, enabled = editable && !locked) { slot = null }
                    for (s in 1..n) {
                        val who = taken[s]
                        SlotChip(s.toString(), selected = slot == s, taken = who != null, extra = s > selTs.maxConnections,
                            enabled = editable && !locked && who == null) { slot = s }
                    }
                }
                if (taken.isNotEmpty()) Hint("Taken: " + taken.toSortedMap().entries.joinToString(", ") { "#${it.key} ${it.value.lastName}" })
                if (slot == null) Hint("Auto = the next free slot is given when it syncs.")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(serial, { serial = it }, "Water meter no. (as printed)", editable && !locked, Modifier.weight(1f), caps = true)
                if (editable && !locked) FilledTonalButton(onClick = { withCamera("scan") }) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Scan") }
            }
            serialDupe?.let { Banner("⚠ Meter no. $serialNorm is already assigned to ${it.fullName} (${it.applicationNo ?: "pending"})", error = true) }

            // ── Status ──
            Section("Status")
            if (status in FIELD_STATUSES && editable) {
                ExposedDropdownMenuBox(expanded = statusMenu, onExpandedChange = { statusMenu = it }) {
                    OutlinedTextField(AppStatus.of(status).label, {}, readOnly = true, label = { Text("Status") },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(AppStatus.color(status))) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        FIELD_STATUSES.forEach { s -> DropdownMenuItem(text = { Text(AppStatus.of(s).label) }, onClick = { status = s; statusMenu = false }) }
                    }
                }
                Hint("Approve, waitlist, reject and connect are done by the office in the web app.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(AppStatus.color(status)))
                    Spacer(Modifier.width(8.dp)); Text(AppStatus.of(status).label, fontWeight = FontWeight.SemiBold)
                }
            }

            Field(remarks, { remarks = it }, "Remarks", editable, Modifier.fillMaxWidth(), minLines = 2)

            // ── Photos ──
            Section("House photos")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (savedPhotos.map { it.filePath to if (it.uploaded) "uploaded" else "waiting" } + newPhotos.map { it to "not saved" }).forEach { (path, tag) ->
                    Box {
                        AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                    }
                }
                if (editable) OutlinedCard(onClick = { withCamera("photo") }, modifier = Modifier.size(96.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.AddAPhoto, "Take photo") }
                }
            }

            if (editable) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(if (isNew) "Save applicant" else "Save changes") }
            } else Hint("View only — your account can't edit.")
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Dialogs ──
    if (confirmOver && selTs != null) {
        AlertDialog(
            onDismissRequest = { confirmOver = false },
            title = { Text("Over design capacity") },
            text = { Text("${selTs.label} already has ${selUsed.size} meters (design capacity ${selTs.maxConnections}). Add this applicant as meter #${selUsed.size + 1}?") },
            confirmButton = { TextButton(onClick = { confirmOver = false; save(force = true) }) { Text("Yes, add") } },
            dismissButton = { TextButton(onClick = { confirmOver = false }) { Text("Choose another") } },
        )
    }
    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BarcodeScanner(onBarcodeDetected = { code -> serial = code.trim().uppercase(); showScanner = false; toast(context, "Scanned: ${serial}") },
                onDismiss = { showScanner = false })
        }
    }
    if (showCamera) {
        Dialog(onDismissRequest = { showCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CameraCapture(onImageCaptured = { path -> newPhotos.add(path); showCamera = false }, onDismiss = { showCamera = false }, lat = lat, lng = lng)
        }
    }
    if (showPicker) {
        var pLat by remember { mutableDoubleStateOf(lat ?: brgyCenter(tapstands, barangayId)?.first ?: PROJECT_LAT) }
        var pLng by remember { mutableDoubleStateOf(lng ?: brgyCenter(tapstands, barangayId)?.second ?: PROJECT_LNG) }
        val sat = remember { context.getSharedPreferences("tt_map", android.content.Context.MODE_PRIVATE).getBoolean("satellite", false) }
        Dialog(onDismissRequest = { showPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Box(Modifier.weight(1f)) {
                        CenterPinMapView(lat = pLat, lng = pLng, satellite = sat, modifier = Modifier.fillMaxSize(), onLocationPicked = { a, b -> pLat = a; pLng = b })
                    }
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Move the map so the pin is on the house  ·  %.6f, %.6f".format(Locale.US, pLat, pLng), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showPicker = false }, Modifier.weight(1f)) { Text("Cancel") }
                            Button(onClick = { latText = "%.6f".format(Locale.US, pLat); lngText = "%.6f".format(Locale.US, pLng); showPicker = false },
                                Modifier.weight(1f)) { Text("Use this location") }
                        }
                    }
                }
            }
        }
    }
    if (showDate) {
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
        val state = rememberDatePickerState(initialSelectedDateMillis = runCatching { fmt.parse(dateApplied)?.time }.getOrNull() ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { dateApplied = fmt.format(Date(it)) }; showDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Average position of the barangay's tapstands — a good starting point for the house picker. */
private fun brgyCenter(ts: List<TapstandEntity>, brgyId: String?): Pair<Double, Double>? {
    val l = ts.filter { brgyId != null && it.barangayId == brgyId }
    return if (l.isEmpty()) null else l.map { it.lat }.average() to l.map { it.lng }.average()
}

private fun toast(context: android.content.Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = Web.Slate700, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Banner(text: String, warn: Boolean = false, error: Boolean = false) {
    val bg = when { error -> MaterialTheme.colorScheme.errorContainer; warn -> Web.Amber50; else -> MaterialTheme.colorScheme.secondaryContainer }
    val fg = when { error -> MaterialTheme.colorScheme.onErrorContainer; warn -> Web.Amber700; else -> MaterialTheme.colorScheme.onSecondaryContainer }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall, color = fg)
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, modifier: Modifier,
    caps: Boolean = false, type: KeyboardType = KeyboardType.Text, minLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled, modifier = modifier,
        singleLine = minLines == 1, minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences),
    )
}

@Composable
private fun SlotChip(text: String, selected: Boolean, taken: Boolean = false, extra: Boolean = false, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val (bg, fg, border) = when {
        selected -> Triple(Web.Teal700, androidx.compose.ui.graphics.Color.White, Web.Teal700)
        taken -> Triple(Web.Slate200, Web.Slate400, Web.Slate200)
        extra -> Triple(androidx.compose.ui.graphics.Color.White, Web.Amber700, Web.Amber700)
        else -> Triple(androidx.compose.ui.graphics.Color.White, Web.Teal700, Web.Teal600)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(36.dp).widthIn(min = 36.dp).clip(shape).background(bg).border(BorderStroke(1.dp, border), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge) }
}
