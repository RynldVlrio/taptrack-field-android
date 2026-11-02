package com.taptrack.app.ui

import androidx.compose.ui.graphics.Color
import com.taptrack.app.data.local.TapstandEntity

/** Same statuses, labels and colours as the web app. */
data class StatusDef(val key: String, val label: String, val color: Long)

object TsStatus {
    val all = listOf(
        StatusDef("proposed", "Proposed", 0xFF6366F1),
        StatusDef("surveyed", "Surveyed", 0xFFD97706),
        StatusDef("approved", "Approved", 0xFF0284C7),
        StatusDef("under_construction", "Under construction", 0xFFEA580C),
        StatusDef("installed", "Installed", 0xFF65A30D),
        StatusDef("operational", "Operational", 0xFF0D9488),
        StatusDef("under_repair", "Under repair", 0xFFE11D48),
        StatusDef("inactive", "Inactive", 0xFF71717A),
        StatusDef("decommissioned", "Decommissioned", 0xFF3F3F46),
        StatusDef("cancelled", "Cancelled", 0xFFA1A1AA),
    )
    /** Statuses an official (verified) tapstand can have. */
    val official = all.filter { it.key !in setOf("proposed", "cancelled") }

    fun of(key: String) = all.firstOrNull { it.key == key } ?: StatusDef(key, key, 0xFF64748B)
    fun color(key: String) = Color(of(key).color)
    fun argb(key: String) = of(key).color.toInt()
}

object AppStatus {
    val all = listOf(
        StatusDef("submitted", "Submitted", 0xFF64748B),
        StatusDef("for_validation", "For validation", 0xFFD97706),
        StatusDef("validated", "Validated", 0xFF0284C7),
        StatusDef("approved", "Approved", 0xFF059669),
        StatusDef("waitlisted", "Waitlisted", 0xFF7C3AED),
        StatusDef("rejected", "Rejected", 0xFFE11D48),
        StatusDef("connected", "Connected", 0xFF0D9488),
        StatusDef("cancelled", "Cancelled", 0xFF71717A),
    )
    fun of(key: String) = all.firstOrNull { it.key == key } ?: StatusDef(key, key, 0xFF64748B)
    fun color(key: String) = Color(of(key).color)
}

/** What kind of record this tapstand is in the workflow. */
enum class TsKind { OFFICIAL, PROPOSED, CANCELLED }

val TapstandEntity.kind: TsKind
    get() = when {
        !isDraft -> TsKind.OFFICIAL
        status == "cancelled" -> TsKind.CANCELLED
        else -> TsKind.PROPOSED
    }

/** Display label: official code, "verifying…" if made official on this phone but not synced yet, else the proposed label. */
val TapstandEntity.label: String
    get() = when {
        code != null -> code
        !isDraft -> "${draftLabel ?: "New"} (code on sync)"
        else -> draftLabel ?: "Proposed"
    }

/** Distance in metres between two points (haversine). */
fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return 2 * r * Math.asin(Math.sqrt(a))
}
