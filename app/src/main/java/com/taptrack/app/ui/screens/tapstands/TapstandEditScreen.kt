package com.taptrack.app.ui.screens.tapstands

import android.Manifest
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.taptrack.app.data.local.PhotoEntity
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.ui.TsKind
import com.taptrack.app.ui.TsStatus
import com.taptrack.app.ui.components.CameraCapture
import com.taptrack.app.ui.components.CenterPinMapView
import com.taptrack.app.ui.distanceM
import com.taptrack.app.ui.kind
import com.taptrack.app.ui.AppStatus
import com.taptrack.app.ui.fullName
import com.taptrack.app.ui.holdsSlot
import com.taptrack.app.ui.theme.Web
import androidx.compose.foundation.clickable
import com.taptrack.app.utils.getLastKnownLocation
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private enum class SaveAction { SAVE, MAKE_OFFICIAL, STAY_PROPOSED, CANCEL, REOPEN }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun TapstandEditScreen(
    container: AppContainer,
    tapstandId: String?,
    initialLat: Double?,
    initialLng: Double?,
    onDone: () -> Unit,
    onOpenApplicant: (String) -> Unit = {},
    onAddApplicant: (String) -> Unit = {},
    onOpenTapstand: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectId = container.session.projectId ?: ""
    val canEdit = if (tapstandId == null) container.session.canAdd else container.session.canEdit
    val canAdd = container.session.canAdd
    val barangays by remember { container.db.barangays().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val allTapstands by remember { container.db.tapstands().observe(projectId) }.collectAsStateWithLifecycle(emptyList())
    val allApplicants by remember { container.db.applicants().observe(projectId) }.collectAsStateWithLifecycle(emptyList())

    // ── Load ─────────────────────────────────────────────────────────────────
    var original by remember { mutableStateOf<TapstandEntity?>(null) }
    var loaded by remember { mutableStateOf(tapstandId == null) }
    val newId = remember { UUID.randomUUID().toString() }
    val entityId = tapstandId ?: newId
    val isNewRecord = tapstandId == null

    var barangayId by remember { mutableStateOf<String?>(null) }
    var draftLabel by remember { mutableStateOf("") }
    var sitio by remember { mutableStateOf("") }
    var locationDesc by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf(initialLat?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var lngText by remember { mutableStateOf(initialLng?.let { "%.6f".format(Locale.US, it) } ?: "") }
    var status by remember { mutableStateOf("surveyed") }
    var officialStatus by remember { mutableStateOf("surveyed") }   // status used when a proposed one is verified
    var capacityText by remember { mutableStateOf("10") }
    var installedOn by remember { mutableStateOf<String?>(null) }
    var remarks by remember { mutableStateOf("") }

    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapstandId, reloadKey) {
        if (tapstandId != null) {
            container.db.tapstands().get(tapstandId)?.let { t ->
                original = t
                barangayId = t.barangayId; draftLabel = t.draftLabel ?: ""; sitio = t.sitio ?: ""
                locationDesc = t.locationDesc ?: ""; latText = "%.6f".format(Locale.US, t.lat); lngText = "%.6f".format(Locale.US, t.lng)
                status = t.status; capacityText = t.maxConnections.toString(); installedOn = t.installedOn; remarks = t.remarks ?: ""
            }
            loaded = true
        } else if (initialLat == null) {
            context.getLastKnownLocation()?.let { latText = "%.6f".format(Locale.US, it.latitude); lngText = "%.6f".format(Locale.US, it.longitude) }
        }
    }

    val kind = original?.kind
    val isProposed = kind == TsKind.PROPOSED
    val isCancelled = kind == TsKind.CANCELLED
    val editable = canEdit && !isCancelled
    val isOfficial = kind == TsKind.OFFICIAL
    val isOffice = container.session.isOffice
    // Surveyed → Installed needs an installation photo taken now (the server checks it too).
    val installing = isOfficial && original?.isNew == false && status == "installed" &&
        original?.status in setOf("surveyed", "approved", "under_construction")

    val savedPhotos by remember(entityId) { container.db.photos().observeFor("tapstand", entityId) }
        .collectAsStateWithLifecycle(emptyList())
    val newPhotos = remember { mutableStateListOf<String>() }

    var showCamera by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var brgyMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var gpsBusy by remember { mutableStateOf(false) }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    val lat = latText.toDoubleOrNull()
    val lng = lngText.toDoubleOrNull()
    val brgy = barangays.firstOrNull { it.id == barangayId }

    // Code preview: next number for the barangay (the server assigns the real one on sync).
    val codePreview = remember(brgy, allTapstands) {
        val c = brgy?.code ?: return@remember null
        val prefix = "$c-TS-"
        val max = allTapstands.mapNotNull { it.code?.takeIf { s -> s.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }.maxOrNull() ?: 0
        prefix + (max + 1).toString().padStart(3, '0')
    }
    val planned: Pair<Double, Double>? = original?.let { o ->
        val pl = o.plannedLat; val pn = o.plannedLng
        if (pl != null && pn != null) Pair(pl, pn) else null
    }
    val movedBy = if (planned != null && lat != null && lng != null) distanceM(planned.first, planned.second, lat, lng) else null

    fun save(action: SaveAction, cancelReason: String? = null) {
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            Toast.makeText(context, "Enter a valid location (latitude / longitude)", Toast.LENGTH_LONG).show(); return
        }
        val goesOfficial = action == SaveAction.MAKE_OFFICIAL || (action == SaveAction.SAVE && (kind == TsKind.OFFICIAL || isNewRecord))
        if (goesOfficial && brgy?.code == null) {
            Toast.makeText(context, "Choose a barangay (with a code) to make this an official tapstand", Toast.LENGTH_LONG).show(); return
        }
        if (action == SaveAction.SAVE && installing && newPhotos.isEmpty()) {
            Toast.makeText(context, "Take an installation photo first (Photos, below)", Toast.LENGTH_LONG).show(); return
        }
        val capacity = capacityText.toIntOrNull()?.coerceAtLeast(1) ?: 10
        val o = original
        val newStatus: String; val newDraft: Boolean
        when (action) {
            SaveAction.SAVE -> if (isNewRecord) { newStatus = status; newDraft = false } else { newStatus = status; newDraft = o!!.isDraft }
            SaveAction.MAKE_OFFICIAL -> { newStatus = officialStatus; newDraft = false }
            SaveAction.STAY_PROPOSED -> { newStatus = "proposed"; newDraft = true }
            SaveAction.CANCEL -> { newStatus = "cancelled"; newDraft = true }
            SaveAction.REOPEN -> { newStatus = "proposed"; newDraft = true }
        }
        var newRemarks = remarks.trim().ifEmpty { null }
        if (action == SaveAction.CANCEL && !cancelReason.isNullOrBlank()) {
            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            newRemarks = listOfNotNull(newRemarks, "Cancelled $stamp: ${cancelReason.trim()}").joinToString("\n")
        }
        val row = (o ?: TapstandEntity(
            id = newId, projectId = projectId, code = null, barangayId = null, sitio = null, locationDesc = null,
            lat = lat, lng = lng, status = newStatus, installedOn = null, remarks = null, isDraft = newDraft,
            draftLabel = null, plannedLat = null, plannedLng = null, planSource = null, isNew = true,
        )).copy(
            barangayId = barangayId, sitio = sitio.trim().ifEmpty { null }, locationDesc = locationDesc.trim().ifEmpty { null },
            lat = lat, lng = lng, status = newStatus, isDraft = newDraft,
            draftLabel = draftLabel.trim().ifEmpty { null } ?: if (newDraft) "Field proposal" else null,
            maxConnections = capacity,
            installedOn = if (action == SaveAction.SAVE && installing) installedOn ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) else installedOn,
            remarks = newRemarks,
            makeOfficial = action == SaveAction.MAKE_OFFICIAL, dirty = true, syncError = null,
            localUpdatedAt = System.currentTimeMillis(),
        )
        scope.launch {
            container.db.tapstands().upsert(row)
            // Surveyed → Installed: the installation photo replaces the survey photos (the server removes its copies too)
            if (action == SaveAction.SAVE && installing) container.db.photos().deleteNonInstallation("tapstand", row.id)
            newPhotos.forEach { path ->
                container.db.photos().insert(PhotoEntity(UUID.randomUUID().toString(), projectId, "tapstand", row.id,
                    kind = if (action == SaveAction.SAVE && installing) "installation" else "photo",
                    filePath = path, lat = lat, lng = lng))
            }
            container.sync.requestSync()
            Toast.makeText(context, when (action) {
                SaveAction.MAKE_OFFICIAL -> "Verified — the code is assigned when it syncs"
                SaveAction.CANCEL -> "Location cancelled"
                SaveAction.REOPEN -> "Reopened as proposed"
                else -> "Saved"
            }, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    val title = when {
        isNewRecord -> "New tapstand"
        original?.code != null -> original!!.code!!
        else -> original?.draftLabel ?: "Proposed tapstand"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (!loaded) { Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        if (tapstandId != null && original == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tapstand not found on this phone.") }; return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Banner ──
            original?.syncError?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                    Text("⚠ The server refused the last change: $it", Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                isProposed -> InfoBanner("Proposed location — not counted yet. Verify it on site to make it official, move it, or cancel it.")
                isCancelled -> InfoBanner("Cancelled location. Reopen it to verify or move it again.")
                isNewRecord -> InfoBanner("\"Add as official\" gives it a code. \"Save as proposed\" keeps it for later verification.")
            }
            if (original?.makeOfficial == true && original?.code == null) InfoBanner("Verified on this phone — the code will be assigned when it syncs.")

            // ── Barangay ──
            ExposedDropdownMenuBox(expanded = brgyMenu && editable, onExpandedChange = { brgyMenu = it }) {
                OutlinedTextField(
                    value = brgy?.let { it.name + (it.code?.let { c -> " ($c)" } ?: "") } ?: "", onValueChange = {},
                    readOnly = true, enabled = editable && original?.code == null, label = { Text("Barangay") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(brgyMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = brgyMenu && editable, onDismissRequest = { brgyMenu = false }) {
                    barangays.forEach { b ->
                        DropdownMenuItem(text = { Text(b.name + (b.code?.let { " ($it)" } ?: "")) }, onClick = { barangayId = b.id; brgyMenu = false })
                    }
                }
            }
            if (original?.code != null) Hint("The barangay can't change once the code is assigned.")
            else if (codePreview != null) Hint("Code when made official: about $codePreview (final number comes from the server)")

            if (original?.code == null) {
                OutlinedTextField(draftLabel, { draftLabel = it }, enabled = editable, singleLine = true,
                    label = { Text("Proposed label (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(sitio, { sitio = it }, enabled = editable, singleLine = true, label = { Text("Sitio / purok") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(locationDesc, { locationDesc = it }, enabled = editable, label = { Text("Location description / landmark") }, modifier = Modifier.fillMaxWidth())

            // ── Location ──
            Text("Location", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(latText, { latText = it }, enabled = editable && !isOfficial, singleLine = true, label = { Text("Latitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(lngText, { lngText = it }, enabled = editable && !isOfficial, singleLine = true, label = { Text("Longitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            if (isOfficial) Hint("To move an official tapstand use ${if (isOffice) "Relocate" else "Request relocation"} below — it keeps its code only within ${RELOCATE_LIMIT_M.toInt()} m.")
            if (editable && !isOfficial) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !gpsBusy, onClick = {
                        scope.launch {
                            gpsBusy = true
                            val loc = context.getLastKnownLocation()
                            if (loc != null) { latText = "%.6f".format(Locale.US, loc.latitude); lngText = "%.6f".format(Locale.US, loc.longitude)
                                Toast.makeText(context, "GPS accuracy ±${loc.accuracy.toInt()} m", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "No GPS fix yet — try again outdoors", Toast.LENGTH_SHORT).show()
                            gpsBusy = false
                        }
                    }) { Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Use GPS") }
                    OutlinedButton(onClick = { showPicker = true }) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(6.dp)); Text("Pick on map") }
                }
            }
            if (movedBy != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint(if (movedBy < 1) "At the planned (KML) location" else "Moved ${movedBy.toInt()} m from the planned location", Modifier.weight(1f))
                    if (editable && !isOfficial && movedBy >= 1) TextButton(onClick = {
                        latText = "%.6f".format(Locale.US, planned!!.first); lngText = "%.6f".format(Locale.US, planned.second)
                    }) { Text("Reset") }
                }
            }

            // ── Status ──
            if (!isCancelled) {
                val statusIsForVerify = isProposed
                val current = if (statusIsForVerify) officialStatus else status
                // decommission / reinstate go through the lifecycle actions; field can't set Inactive
                val statusLocked = isOfficial && (original?.status == "decommissioned" || (original?.status in RETIRED && !isOffice))
                // Installed: only for an official tapstand already on the server (needs an installation photo);
                // a new or just-verified tapstand starts as Surveyed.
                val canInstall = isOfficial && original?.isNew == false
                val choices = TsStatus.official.filter { s ->
                    s.key == original?.status || (s.key != "decommissioned" && (s.key != "inactive" || isOffice || !isOfficial) &&
                        (s.key != "installed" || canInstall))
                }
                val statusEditable = editable && !statusLocked
                ExposedDropdownMenuBox(expanded = statusMenu && statusEditable, onExpandedChange = { statusMenu = it }) {
                    OutlinedTextField(
                        value = TsStatus.of(current).label, onValueChange = {}, readOnly = true, enabled = statusEditable,
                        label = { Text(if (statusIsForVerify) "Status when made official" else "Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = statusMenu && statusEditable, onDismissRequest = { statusMenu = false }) {
                        choices.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = {
                                if (statusIsForVerify) officialStatus = s.key else status = s.key; statusMenu = false
                            })
                        }
                    }
                }
                if (isNewRecord) Hint("Status applies when added as official. Mark it Installed later, with an installation photo.")
                if (installing) Surface(color = Web.Amber50, shape = RoundedCornerShape(8.dp)) {
                    Text("📷 Installed needs an installation photo — take it under Photos below, then Save. It replaces the survey photo(s).",
                        Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall, color = Web.Amber700)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(capacityText, { capacityText = it.filter(Char::isDigit) }, enabled = editable, singleLine = true,
                    label = { Text("Design capacity (meters)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f))
                OutlinedTextField(installedOn ?: "", {}, readOnly = true, enabled = editable, label = { Text("Installed on") },
                    trailingIcon = {
                        Row {
                            if (installedOn != null && editable) IconButton(onClick = { installedOn = null }) { Icon(Icons.Default.Clear, "Clear date") }
                            IconButton(onClick = { if (editable) showDate = true }) { Icon(Icons.Default.CalendarMonth, "Pick date") }
                        }
                    },
                    modifier = Modifier.weight(1f))
            }
            OutlinedTextField(remarks, { remarks = it }, enabled = editable, label = { Text("Remarks") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            // ── Meters on this tapstand (official only) ──
            if (kind == TsKind.OFFICIAL && tapstandId != null) {
                val meters = allApplicants.filter { it.assignedTapstandId == tapstandId && it.holdsSlot }.sortedBy { it.meterSlot ?: 99 }
                val cap = original?.maxConnections ?: 10
                Text("Meters  ${meters.size} / $cap" + if (meters.size >= cap) "  · at design capacity" else "",
                    style = MaterialTheme.typography.titleSmall, color = if (meters.size >= cap) Web.Amber700 else MaterialTheme.colorScheme.onSurface)
                if (meters.isEmpty()) Hint("No applicants assigned yet.")
                meters.forEach { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onOpenApplicant(a.id) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("#${a.meterSlot ?: "?"}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.fullName, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(AppStatus.of(a.status).label, a.meterSerial?.let { "meter $it" } ?: "no meter no. yet").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (canAdd && original?.status !in setOf("inactive", "decommissioned")) {
                    OutlinedButton(onClick = { onAddApplicant(tapstandId) }) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("New applicant on this tapstand") }
                }
                HorizontalDivider()
                TapstandLifecycleSection(
                    container = container, ts = original!!, meters = meters.size,
                    codeOf = { id -> allTapstands.firstOrNull { it.id == id }?.code ?: "another tapstand" },
                    onOpenTapstand = onOpenTapstand,
                    onChanged = { reloadKey++ },
                )
                HorizontalDivider()
            }

            // ── Photos ──
            Text(if (installing) "Photos — installation photo required" else "Photos", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (savedPhotos.map { it.filePath to (if (installing && it.kind != "installation") "will be replaced" else if (it.uploaded) "uploaded" else "waiting") } +
                    newPhotos.map { it to (if (installing) "installation" else "not saved") }).forEach { (path, tag) ->
                    Box {
                        AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                    }
                }
                if (editable) {
                    OutlinedCard(onClick = { if (cameraPermission.status.isGranted) showCamera = true else cameraPermission.launchPermissionRequest() },
                        modifier = Modifier.size(96.dp)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddAPhoto, "Take photo")
                            if (installing) Text("Installation", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            // photos already on the server (any phone), e.g. the installation photo
            if (!isNewRecord && original?.isNew == false) {
                com.taptrack.app.ui.components.RemotePhotos(container, "tapstand", entityId, skipIds = savedPhotos.map { it.id }.toSet(),
                    replacing = installing)
            }

            // ── Actions ──
            Spacer(Modifier.height(4.dp))
            if (canEdit) {
                when {
                    isNewRecord -> {
                        Button(onClick = { save(SaveAction.SAVE) }, Modifier.fillMaxWidth()) { Text("Add as official") }
                        OutlinedButton(onClick = { save(SaveAction.STAY_PROPOSED) }, Modifier.fillMaxWidth()) { Text("Save as proposed") }
                    }
                    isProposed -> {
                        Button(onClick = { save(SaveAction.MAKE_OFFICIAL) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Verified, null); Spacer(Modifier.width(6.dp)); Text("Verify & make official")
                        }
                        OutlinedButton(onClick = { save(SaveAction.STAY_PROPOSED) }, Modifier.fillMaxWidth()) { Text("Save (stay proposed)") }
                        TextButton(onClick = { showCancel = true }, Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Cancel this location…") }
                    }
                    isCancelled -> OutlinedButton(onClick = { save(SaveAction.REOPEN) }, Modifier.fillMaxWidth()) { Text("Reopen as proposed") }
                    else -> {
                        Button(onClick = { save(SaveAction.SAVE) }, Modifier.fillMaxWidth()) { Text("Save") }
                        Hint("Official tapstands keep their code for good — use the lifecycle actions above to relocate, decommission or reinstate.")
                    }
                }
            } else Hint("View only — your account can't edit.")
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Dialogs ──
    if (showCamera) {
        Dialog(onDismissRequest = { showCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CameraCapture(
                onImageCaptured = { path -> newPhotos.add(path); showCamera = false },
                onDismiss = { showCamera = false },
                lat = lat, lng = lng,
            )
        }
    }
    if (showPicker) {
        var pLat by remember { mutableDoubleStateOf(lat ?: com.taptrack.app.ui.components.PROJECT_LAT) }
        var pLng by remember { mutableDoubleStateOf(lng ?: com.taptrack.app.ui.components.PROJECT_LNG) }
        val sat = remember { context.getSharedPreferences("tt_map", android.content.Context.MODE_PRIVATE).getBoolean("satellite", false) }
        Dialog(onDismissRequest = { showPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Box(Modifier.weight(1f)) {
                        CenterPinMapView(lat = pLat, lng = pLng, satellite = sat, modifier = Modifier.fillMaxSize(),
                            onLocationPicked = { a, b -> pLat = a; pLng = b })
                    }
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Move the map so the pin is on the spot  ·  %.6f, %.6f".format(Locale.US, pLat, pLng), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showPicker = false }, Modifier.weight(1f)) { Text("Cancel") }
                            Button(onClick = {
                                latText = "%.6f".format(Locale.US, pLat); lngText = "%.6f".format(Locale.US, pLng); showPicker = false
                            }, Modifier.weight(1f)) { Text("Use this location") }
                        }
                    }
                }
            }
        }
    }
    if (showDate) {
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
        val init = installedOn?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: System.currentTimeMillis()
        val state = rememberDatePickerState(initialSelectedDateMillis = init)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { installedOn = fmt.format(Date(it)) }; showDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (showCancel) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCancel = false },
            title = { Text("Cancel this proposed location?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("It won't be counted or assignable. You can reopen it later.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { showCancel = false; save(SaveAction.CANCEL, reason) }) { Text("Cancel location") } },
            dismissButton = { TextButton(onClick = { showCancel = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun InfoBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
