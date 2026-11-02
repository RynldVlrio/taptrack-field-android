package com.taptrack.app.data.sync

import com.taptrack.app.data.local.ApplicantEntity
import com.taptrack.app.data.local.BarangayEntity
import com.taptrack.app.data.local.LocalDb
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
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val mutex = Mutex()

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
            val refused = pushTapstands() + pushApplicants()
            pushPhotos(projectId)
            pullBarangays(projectId)
            pullTapstands(projectId)
            pullApplicants(projectId)
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

    private suspend fun pushPhotos(projectId: String) {
        val stillLocal = (db.tapstands().dirtyIds() + db.applicants().dirtyIds()).toSet()
        for (p in db.photos().pending()) {
            if (p.entityId in stillLocal) continue           // upload the record first
            val file = File(p.filePath)
            if (!file.exists()) { db.photos().markError(p.id, "File missing"); continue }
            try {
                val path = "$projectId/${p.entityType}/${p.id}.jpg"
                api.uploadFile("media", path, file)
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
        const val TAPSTAND_COLS = "id,project_id,code,barangay_id,sitio,location_desc,lat,lng,status,max_connections," +
            "installed_on,remarks,is_draft,draft_label,planned_lat,planned_lng,plan_source,deleted_at,sync_version"
        const val APPLICANT_COLS = "id,project_id,application_no,last_name,first_name,middle_name,suffix,contact_no," +
            "barangay_id,sitio,address,household_size,account_type,lat,lng,status,date_applied,assigned_tapstand_id," +
            "meter_slot,meter_serial,decision_reason,remarks,deleted_at,sync_version"
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
    put("lat", lat); put("lng", lng)
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
}
