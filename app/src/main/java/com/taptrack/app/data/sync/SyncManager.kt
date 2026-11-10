package com.taptrack.app.data.sync

import com.taptrack.app.data.local.ApplicantEntity
import com.taptrack.app.data.local.BarangayEntity
import com.taptrack.app.data.local.LeadEntity
import com.taptrack.app.data.local.LocalDb
import com.taptrack.app.data.local.MapLayerEntity
import com.taptrack.app.data.local.SyncMetaEntity
import com.taptrack.app.data.local.TapstandEntity
import com.taptrack.app.data.remote.ApiException
import com.taptrack.app.data.remote.AuthExpiredException
import com.taptrack.app.data.remote.SessionStore
import com.taptrack.app.data.remote.SupabaseClient
import com.taptrack.app.data.remote.optDoubleOrNull
import com.taptrack.app.data.remote.optIntOrNull
import com.taptrack.app.data.remote.optStringOrNull
import com.taptrack.app.data.remote.putNullable
import com.taptrack.app.utils.PhotoCompress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class SyncState(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val offline: Boolean = false,
    val message: String? = null,     // last problem, shown in the More screen
    val authExpired: Boolean = false,
)

/**
 * Offline-first sync:
 *   1) upload local changes (tapstands, applicants, photos)
 *   2) download everything changed on the server since the last sync (by sync_version)
 * Runs automatically every minute while the app is open, after each save, and on demand.
 */
class SyncManager(
    private val db: LocalDb,
    private val api: SupabaseClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
    private val layersDir: File,          // where shared map layers (GeoJSON) are kept for offline use
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Extra uploads run with every sync (set by the app: As-Built surveys saved while offline). */
    var extraUploads: (suspend () -> Unit)? = null

    fun startAutoSync() {
        scope.launch {
            while (isActive) {
                if (session.loggedIn.value && !_state.value.authExpired) runCatching { syncNow() }
                delay(60_000)
            }
        }
    }

    /** Fire-and-forget sync (e.g. right after a save). */
    fun requestSync() { scope.launch { runCatching { syncNow() } } }

    suspend fun syncNow(): Boolean = mutex.withLock {
        val projectId = session.projectId ?: return@withLock false
        _state.update { it.copy(running = true) }
        try {
            pushPhotos(projectId)          // photos of records already on the server first (Installed needs its photo there)
            val refused = pushTapstands() + pushApplicants()
            pushPhotos(projectId)          // then photos of records just created
            extraUploads?.let { up -> try { up() } catch (e: ApiException) { /* retried next sync */ } }
            pullBarangays(projectId)
            pullTapstands(projectId)
            pullApplicants(projectId)
            pullLeads(projectId)
            pruneRemoved(projectId)
            pullMapLayers(projectId)
            downloadMapLayers()
            val now = System.currentTimeMillis()
            db.syncMeta().put(SyncMetaEntity("last_sync", now.toString()))
            _state.value = SyncState(
                running = false, lastSyncAt = now,
                message = if (refused > 0) "$refused change(s) were refused by the server — open the items marked ⚠ to fix them" else null,
            )
            true
        } catch (e: AuthExpiredException) {
            _state.update { it.copy(running = false, authExpired = true, message = e.message) }
            false
        } catch (e: IOException) {
            _state.update { it.copy(running = false, offline = true, message = "Offline — changes are saved on this phone and will upload when online") }
            false
        } catch (e: Exception) {
            _state.update { it.copy(running = false, message = e.message ?: e.toString()) }
            false
        }
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    /** Allow sync again after the user signs back in. */
    fun clearAuthExpired() { _state.update { it.copy(authExpired = false, message = null) } }

    private suspend fun pushTapstands(): Int {
        var refused = 0
        for (t in db.tapstands().dirty()) {
            try {
                val stored = if (t.isNew) api.insert("tapstands", t.toServerJson(withKeys = true))
                             else api.update("tapstands", t.id, t.toServerJson(withKeys = false))
                db.tapstands().upsert(tapstandFromServer(stored))
            } catch (e: ApiException) {
                refused++
                db.tapstands().upsert(t.copy(syncError = e.message))
            }
        }
        return refused
    }

    private suspend fun pushApplicants(): Int {
        var refused = 0
        for (a in db.applicants().dirty()) {
            try {
                val stored = if (a.isNew) api.insert("applicants", a.toServerJson(withKeys = true))
                             else api.update("applicants", a.id, a.toServerJson(withKeys = false))
                db.applicants().upsert(applicantFromServer(stored))
            } catch (e: ApiException) {
                refused++
                db.applicants().upsert(a.copy(syncError = e.message))
            }
        }
        return refused
    }

    /** Google Drive photo store URL set on the web (Settings → Photo storage), or null = Supabase storage. */
    private suspend fun photoStoreUrl(): String? = runCatching {
        api.selectAnon("app_config?select=value&key=eq.photoStoreUrl").optJSONObject(0)?.optString("value")?.trim()
    }.getOrNull()?.takeIf { it.startsWith("https://") }

    /** Folder name for a photo in Drive: tapstand code / proposed label, or applicant name. */
    private suspend fun photoLabel(entityType: String, entityId: String): String = when (entityType) {
        "tapstand" -> db.tapstands().get(entityId)?.let { it.code ?: it.draftLabel ?: "Proposed" } ?: "Tapstand"
        "applicant" -> db.applicants().get(entityId)?.let { listOf(it.lastName, it.firstName).joinToString(", ") } ?: "Applicant"
        else -> entityType
    }

    private suspend fun pushPhotos(projectId: String) {
        val stillLocal = (db.tapstands().newIds() + db.applicants().newIds()).toSet()
        val pending = db.photos().pending().filter { it.entityId !in stillLocal }   // record not on the server yet: upload it first
        if (pending.isEmpty()) return
        val storeUrl = photoStoreUrl()
        val tmp = File(layersDir.parentFile ?: layersDir, "photo_tmp").apply { mkdirs() }
        for (p in pending) {
            val file = File(p.filePath)
            if (!file.exists()) { db.photos().markError(p.id, "File missing"); continue }
            try {
                // ~0.3 MB instead of 3–5 MB (plus a small thumbnail)
                val small = runCatching { PhotoCompress.compress(file, tmp) }.getOrNull()
                val path = if (storeUrl != null) {
                    val full = small?.full ?: file.readBytes()
                    val r = api.photoStore(storeUrl, JSONObject()
                        .put("action", "upload").put("project", projectId).put("id", p.id)
                        .put("entityType", p.entityType).put("label", photoLabel(p.entityType, p.entityId)).put("kind", p.kind)
                        .put("takenAt", java.time.Instant.ofEpochMilli(p.takenAt).toString())
                        .put("full", android.util.Base64.encodeToString(full, android.util.Base64.NO_WRAP))
                        .put("thumb", small?.thumb?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""))
                    "gdrive:" + r.getString("fullId") + (r.optString("thumbId").takeIf { it.isNotBlank() }?.let { "|$it" } ?: "")
                } else {
                    val path = "$projectId/${p.entityType}/${p.id}.jpg"
                    val upload = small?.let { File(tmp, "${p.id}.jpg").apply { writeBytes(it.full) } } ?: file
                    try { api.uploadFile("media", path, upload) } finally { if (upload != file) upload.delete() }
                    path
                }
                api.insert("attachments", JSONObject()
                    .put("id", p.id).put("project_id", projectId)
                    .put("entity_type", p.entityType).put("entity_id", p.entityId)
                    .put("kind", p.kind).put("storage_path", path)
                    .put("taken_at", java.time.Instant.ofEpochMilli(p.takenAt).toString())
                    .putNullable("lat", p.lat).putNullable("lng", p.lng))
                db.photos().markUploaded(p.id)
            } catch (e: ApiException) {
                if (e.code == 409) db.photos().markUploaded(p.id)   // already recorded
                else db.photos().markError(p.id, e.message)
            }
        }
    }

    // ── Download (delta by sync_version) ──────────────────────────────────────

    private suspend fun pullBarangays(projectId: String) = pullTable(projectId, "barangays",
        "id,project_id,name,code,deleted_at,sync_version") { rows ->
        db.barangays().upsertAll(rows.map {
            BarangayEntity(it.getString("id"), it.getString("project_id"), it.getString("name"),
                it.optStringOrNull("code"), deleted = !it.isNull("deleted_at"))
        })
    }

    private suspend fun pullTapstands(projectId: String) = pullTable(projectId, "tapstands", TAPSTAND_COLS) { rows ->
        val dirty = db.tapstands().dirtyIds().toSet()
        db.tapstands().upsertAll(rows.filter { it.getString("id") !in dirty }.map { tapstandFromServer(it) })
    }

    private suspend fun pullApplicants(projectId: String) = pullTable(projectId, "applicants", APPLICANT_COLS) { rows ->
        val dirty = db.applicants().dirtyIds().toSet()
        db.applicants().upsertAll(rows.filter { it.getString("id") !in dirty }.map { applicantFromServer(it) })
    }

    /**
     * HH Survey leads: full replace (a CSV re-upload deletes every lead, which a sync_version delta can't see).
     * Only downloads when the newest lead changed, or every 6 h as a safety net. Viewers get nothing (database rule).
     * Repeated survey record IDs (same survey submitted twice) are kept once.
     */
    private suspend fun pullLeads(projectId: String) {
        try {
            val newest = api.select("leads?select=sync_version&project_id=eq.$projectId&deleted_at=is.null&order=sync_version.desc&limit=1")
            val marker = if (newest.length() == 0) "none" else newest.getJSONObject(0).optLong("sync_version").toString()
            val lastMarker = db.syncMeta().get("leads_marker")
            val lastAt = db.syncMeta().get("leads_at")?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            if (marker == lastMarker && now - lastAt < 6 * 3_600_000L) return

            val rows = mutableListOf<JSONObject>()
            var offset = 0
            while (true) {
                val arr = api.select("leads?select=$LEAD_COLS&project_id=eq.$projectId&deleted_at=is.null&order=id.asc&limit=1000&offset=$offset")
                for (i in 0 until arr.length()) rows += arr.getJSONObject(i)
                if (arr.length() < 1000) break
                offset += 1000
            }
            val seen = HashSet<String>()
            val leads = rows.map { leadFromServer(it) }.filter { l -> l.recordUuid == null || seen.add(l.recordUuid) }
            db.leads().replaceAll(projectId, leads)
            db.syncMeta().put(SyncMetaEntity("leads_marker", marker))
            db.syncMeta().put(SyncMetaEntity("leads_at", now.toString()))
        } catch (e: ApiException) {
            // leads not available to this account (or not set up yet) — tapstands/applicants sync carries on
        }
    }

    /**
     * Records deleted outright on the server (e.g. a web cleanup) never show up in the sync_version delta,
     * so the phone would keep them. Every 6 h (and right after this update) compare ids with the server and
     * drop local copies the server no longer has. Rows with unsent changes on this phone are never touched.
     */
    private suspend fun pruneRemoved(projectId: String) {
        val now = System.currentTimeMillis()
        val last = db.syncMeta().get("prune_at")?.toLongOrNull() ?: 0L
        if (now - last < 6 * 3_600_000L) return
        try {
            val serverApplicants = serverIds("applicants", projectId)
            val goneApplicants = db.applicants().syncedIds(projectId).filter { it !in serverApplicants }
            goneApplicants.chunked(500).forEach { db.applicants().deleteIds(it) }

            val serverTapstands = serverIds("tapstands", projectId)
            val goneTapstands = db.tapstands().syncedIds(projectId).filter { it !in serverTapstands }
            goneTapstands.chunked(500).forEach { db.tapstands().deleteIds(it) }

            db.syncMeta().put(SyncMetaEntity("prune_at", now.toString()))
        } catch (e: ApiException) {
            // try again next sync
        }
    }

    private suspend fun serverIds(table: String, projectId: String): Set<String> {
        val ids = HashSet<String>()
        var offset = 0
        while (true) {
            val arr = api.select("$table?select=id&project_id=eq.$projectId&order=id.asc&limit=1000&offset=$offset")
            for (i in 0 until arr.length()) ids += arr.getJSONObject(i).getString("id")
            if (arr.length() < 1000) break
            offset += 1000
        }
        return ids
    }

    private suspend fun pullMapLayers(projectId: String) = pullTable(projectId, "map_layers", MAP_LAYER_COLS) { rows ->
        val existing = db.mapLayers().all().associateBy { it.id }
        db.mapLayers().upsertAll(rows.map { o ->
            val old = existing[o.getString("id")]
            mapLayerFromServer(o).copy(localFile = old?.localFile, localVersion = old?.localVersion, userVisible = old?.userVisible)
        })
    }

    /** Keep a local copy of every shared layer; re-download when the office changes it; delete removed ones. */
    private suspend fun downloadMapLayers() {
        layersDir.mkdirs()
        for (l in db.mapLayers().all()) {
            val file = File(layersDir, "${l.id}.geojson")
            if (l.deleted) {
                if (l.localFile != null) { file.delete(); db.mapLayers().markDownloaded(l.id, null, null) }
                continue
            }
            if (l.localVersion == l.updatedAt && file.exists()) continue
            try {
                val bytes = api.downloadFile("media", l.storagePath)
                val tmp = File(layersDir, "${l.id}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                db.mapLayers().markDownloaded(l.id, file.absolutePath, l.updatedAt)
            } catch (e: ApiException) {
                // file missing or not allowed — keep the old copy, try again next sync
            }
        }
    }

    private suspend fun pullTable(projectId: String, table: String, cols: String, save: suspend (List<JSONObject>) -> Unit) {
        val key = "v_$table"
        var since = db.syncMeta().get(key)?.toLongOrNull() ?: 0L
        while (true) {
            val arr = api.select("$table?select=$cols&project_id=eq.$projectId&sync_version=gt.$since&order=sync_version.asc&limit=1000")
            if (arr.length() == 0) break
            val rows = (0 until arr.length()).map { arr.getJSONObject(it) }
            save(rows)
            since = rows.maxOf { it.getLong("sync_version") }
            db.syncMeta().put(SyncMetaEntity(key, since.toString()))
            if (arr.length() < 1000) break
        }
    }

    companion object {
        const val MAP_LAYER_COLS = "id,project_id,name,storage_path,file_type,style,is_visible,sort_order,updated_at,deleted_at,sync_version"
        const val TAPSTAND_COLS = "id,project_id,code,barangay_id,sitio,location_desc,lat,lng,status,max_connections," +
            "installed_on,remarks,is_draft,draft_label,planned_lat,planned_lng,plan_source,deleted_at,sync_version"
        const val APPLICANT_COLS = "id,project_id,application_no,last_name,first_name,middle_name,suffix,contact_no," +
            "barangay_id,sitio,address,household_size,account_type,lat,lng,status,date_applied,assigned_tapstand_id," +
            "meter_slot,meter_serial,decision_reason,remarks,source_lead_uuid,deleted_at,sync_version"
        /** No income_range: not downloaded to phones. */
        const val LEAD_COLS = "id,project_id,record_uuid,survey_date,barangay_id,barangay_name,zone,first_name,last_name," +
            "contact_no,household_members,household_ownership,existing_water_source,drinking_water_source," +
            "willingness_to_connect,remarks,lat,lng,google_maps_link,photo_drive_link"
    }
}

// ── Mapping server JSON ⇄ local rows ───────────────────────────────────────────

fun tapstandFromServer(o: JSONObject) = TapstandEntity(
    id = o.getString("id"),
    projectId = o.getString("project_id"),
    code = o.optStringOrNull("code"),
    barangayId = o.optStringOrNull("barangay_id"),
    sitio = o.optStringOrNull("sitio"),
    locationDesc = o.optStringOrNull("location_desc"),
    lat = o.getDouble("lat"),
    lng = o.getDouble("lng"),
    status = o.getString("status"),
    maxConnections = o.optIntOrNull("max_connections") ?: 10,
    installedOn = o.optStringOrNull("installed_on"),
    remarks = o.optStringOrNull("remarks"),
    isDraft = o.optBoolean("is_draft", false),
    draftLabel = o.optStringOrNull("draft_label"),
    plannedLat = o.optDoubleOrNull("planned_lat"),
    plannedLng = o.optDoubleOrNull("planned_lng"),
    planSource = o.optStringOrNull("plan_source"),
    deleted = !o.isNull("deleted_at"),
    syncVersion = o.optLong("sync_version", 0),
)

fun TapstandEntity.toServerJson(withKeys: Boolean): JSONObject = JSONObject().apply {
    if (withKeys) { put("id", id); put("project_id", projectId) }
    putNullable("barangay_id", barangayId)
    putNullable("sitio", sitio)
    putNullable("location_desc", locationDesc)
    // official tapstands are only moved through the online Relocate action (50 m rule); never resend a stale spot
    if (withKeys || code == null) { put("lat", lat); put("lng", lng) }
    put("status", status)
    put("max_connections", maxConnections)
    putNullable("installed_on", installedOn)
    putNullable("remarks", remarks)
    put("is_draft", isDraft)
    putNullable("draft_label", draftLabel)
    if (withKeys) { putNullable("planned_lat", plannedLat); putNullable("planned_lng", plannedLng); putNullable("plan_source", planSource) }
}

fun applicantFromServer(o: JSONObject) = ApplicantEntity(
    id = o.getString("id"),
    projectId = o.getString("project_id"),
    applicationNo = o.optStringOrNull("application_no"),
    lastName = o.getString("last_name"),
    firstName = o.getString("first_name"),
    middleName = o.optStringOrNull("middle_name"),
    suffix = o.optStringOrNull("suffix"),
    contactNo = o.optStringOrNull("contact_no"),
    barangayId = o.optStringOrNull("barangay_id"),
    sitio = o.optStringOrNull("sitio"),
    address = o.optStringOrNull("address"),
    householdSize = o.optIntOrNull("household_size"),
    accountType = o.optStringOrNull("account_type") ?: "residential",
    lat = o.optDoubleOrNull("lat"),
    lng = o.optDoubleOrNull("lng"),
    status = o.getString("status"),
    dateApplied = o.optStringOrNull("date_applied") ?: "",
    assignedTapstandId = o.optStringOrNull("assigned_tapstand_id"),
    meterSlot = o.optIntOrNull("meter_slot"),
    meterSerial = o.optStringOrNull("meter_serial"),
    decisionReason = o.optStringOrNull("decision_reason"),
    remarks = o.optStringOrNull("remarks"),
    deleted = !o.isNull("deleted_at"),
    syncVersion = o.optLong("sync_version", 0),
    sourceLeadUuid = o.optStringOrNull("source_lead_uuid"),
)

fun leadFromServer(o: JSONObject) = LeadEntity(
    id = o.getString("id"),
    projectId = o.getString("project_id"),
    recordUuid = o.optStringOrNull("record_uuid")?.trim()?.ifEmpty { null },
    surveyDate = o.optStringOrNull("survey_date"),
    barangayId = o.optStringOrNull("barangay_id"),
    barangayName = o.optStringOrNull("barangay_name"),
    zone = o.optStringOrNull("zone"),
    firstName = o.optStringOrNull("first_name") ?: "",
    lastName = o.optStringOrNull("last_name") ?: "",
    contactNo = o.optStringOrNull("contact_no"),
    householdMembers = o.optIntOrNull("household_members"),
    householdOwnership = o.optStringOrNull("household_ownership"),
    existingWaterSource = o.optStringOrNull("existing_water_source"),
    drinkingWaterSource = o.optStringOrNull("drinking_water_source"),
    willingness = o.optStringOrNull("willingness_to_connect"),
    remarks = o.optStringOrNull("remarks"),
    lat = o.optDoubleOrNull("lat"),
    lng = o.optDoubleOrNull("lng"),
    mapsLink = o.optStringOrNull("google_maps_link"),
    photoLink = o.optStringOrNull("photo_drive_link"),
)

fun ApplicantEntity.toServerJson(withKeys: Boolean): JSONObject = JSONObject().apply {
    if (withKeys) { put("id", id); put("project_id", projectId) }
    put("last_name", lastName); put("first_name", firstName)
    putNullable("middle_name", middleName); putNullable("suffix", suffix)
    putNullable("contact_no", contactNo)
    putNullable("barangay_id", barangayId); putNullable("sitio", sitio); putNullable("address", address)
    putNullable("household_size", householdSize)
    put("account_type", accountType)
    putNullable("lat", lat); putNullable("lng", lng)
    put("status", status)
    if (dateApplied.isNotBlank()) put("date_applied", dateApplied)
    putNullable("assigned_tapstand_id", assignedTapstandId)
    putNullable("meter_slot", meterSlot)
    putNullable("meter_serial", meterSerial)
    putNullable("remarks", remarks)
    putNullable("source_lead_uuid", sourceLeadUuid)
}

// ── Shared map layers ─────────────────────────────────────────────────────────

/** "#RRGGBB" or "#AARRGGBB" → ARGB int (fallback teal). */
private fun parseHex(c: String?, fallback: Int = 0xFF0F766E.toInt()): Int = try {
    val h = c?.trim()?.removePrefix("#") ?: ""
    when (h.length) {
        6 -> (0xFF000000 or h.toLong(16)).toInt()
        8 -> h.toLong(16).toInt()
        else -> fallback
    }
} catch (_: Exception) { fallback }

fun mapLayerFromServer(o: JSONObject): MapLayerEntity {
    val st = o.optJSONObject("style") ?: JSONObject()
    val stroke = parseHex(st.optStringOrNull("stroke")) or 0xFF000000.toInt()
    val fillRaw = st.optStringOrNull("fill")
    val fillRgb = parseHex(fillRaw, stroke) and 0x00FFFFFF
    val opacity = when {
        st.has("fillOpacity") -> st.optDouble("fillOpacity", 0.12)
        fillRaw?.removePrefix("#")?.length == 8 -> ((parseHex(fillRaw) ushr 24) and 0xFF) / 255.0
        else -> 0.12
    }.coerceIn(0.0, 1.0)
    return MapLayerEntity(
        id = o.getString("id"),
        projectId = o.getString("project_id"),
        name = o.optString("name", "Layer"),
        storagePath = o.getString("storage_path"),
        fileType = o.optString("file_type", "geojson"),
        strokeColor = stroke,
        fillColor = ((opacity * 255).toInt() shl 24) or fillRgb,
        weight = st.optDouble("weight", 2.0).toFloat(),
        showLabel = st.optBoolean("showLabel", true),
        defaultVisible = o.optBoolean("is_visible", true),
        deleted = !o.isNull("deleted_at"),
        syncVersion = o.optLong("sync_version", 0),
        updatedAt = o.optString("updated_at", ""),
        sortOrder = o.optInt("sort_order", 0),
    )
}
