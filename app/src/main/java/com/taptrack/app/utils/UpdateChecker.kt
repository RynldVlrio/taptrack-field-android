package com.taptrack.app.utils

import android.content.SharedPreferences
import com.taptrack.app.data.remote.SupabaseClient
import java.util.concurrent.TimeUnit

// App update control — reads the `app_config` table in Supabase (replaces the old Apps Script sheet).
// Keys: status (ACTIVE / anything else = maintenance), message, latestVersionCode,
//       minSupportedVersionCode, apkUrl, graceDays

private const val PREFS_KEY_FIRST_UPDATE_SEEN = "first_update_seen_ms"

data class AppControlConfig(
    val status: String,
    val message: String,
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int,
    val apkUrl: String,
    val graceDays: Int,
)

sealed class AppUpdateState {
    object Normal : AppUpdateState()
    data class UpdateAvailable(val apkUrl: String, val latestVersionCode: Int) : AppUpdateState()
    data class ForceUpdate(val apkUrl: String, val latestVersionCode: Int) : AppUpdateState()
    data class Maintenance(val message: String) : AppUpdateState()
}

suspend fun fetchAppControl(api: SupabaseClient): AppControlConfig? = try {
    val rows = api.selectAnon("app_config?select=key,value")
    val map = (0 until rows.length()).associate { rows.getJSONObject(it).getString("key") to rows.getJSONObject(it).getString("value") }
    AppControlConfig(
        status = map["status"] ?: "ACTIVE",
        message = map["message"] ?: "",
        latestVersionCode = map["latestVersionCode"]?.toIntOrNull() ?: 0,
        minSupportedVersionCode = map["minSupportedVersionCode"]?.toIntOrNull() ?: 0,
        apkUrl = map["apkUrl"] ?: "",
        graceDays = map["graceDays"]?.toIntOrNull() ?: 7,
    )
} catch (_: Exception) {
    null // offline or not configured → app works normally
}

fun resolveUpdateState(config: AppControlConfig, currentVersionCode: Int, prefs: SharedPreferences): AppUpdateState {
    if (config.status.uppercase() != "ACTIVE") return AppUpdateState.Maintenance(config.message)
    if (config.apkUrl.isBlank()) return AppUpdateState.Normal
    if (currentVersionCode < config.minSupportedVersionCode) return AppUpdateState.ForceUpdate(config.apkUrl, config.latestVersionCode)
    if (currentVersionCode < config.latestVersionCode) {
        val now = System.currentTimeMillis()
        val firstSeen = prefs.getLong(PREFS_KEY_FIRST_UPDATE_SEEN, 0L)
        if (firstSeen == 0L) {
            prefs.edit().putLong(PREFS_KEY_FIRST_UPDATE_SEEN, now).apply()
        } else if (TimeUnit.MILLISECONDS.toDays(now - firstSeen) >= config.graceDays) {
            return AppUpdateState.ForceUpdate(config.apkUrl, config.latestVersionCode)
        }
        return AppUpdateState.UpdateAvailable(config.apkUrl, config.latestVersionCode)
    }
    prefs.edit().remove(PREFS_KEY_FIRST_UPDATE_SEEN).apply()
    return AppUpdateState.Normal
}
