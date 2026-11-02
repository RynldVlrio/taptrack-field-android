package com.taptrack.app.data.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Signed-in user + chosen project, kept in app-private SharedPreferences. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("tt_session", Context.MODE_PRIVATE)

    private val _loggedIn = MutableStateFlow(prefs.getString(K_REFRESH, null) != null && prefs.getString(K_PROJECT, null) != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    var accessToken: String?
        get() = prefs.getString(K_ACCESS, null)
        private set(v) = prefs.edit().putString(K_ACCESS, v).apply()
    var refreshToken: String?
        get() = prefs.getString(K_REFRESH, null)
        private set(v) = prefs.edit().putString(K_REFRESH, v).apply()
    /** epoch seconds */
    var expiresAt: Long
        get() = prefs.getLong(K_EXPIRES, 0L)
        private set(v) = prefs.edit().putLong(K_EXPIRES, v).apply()

    val userId: String? get() = prefs.getString(K_USER, null)
    val email: String? get() = prefs.getString(K_EMAIL, null)
    val fullName: String get() = prefs.getString(K_NAME, null) ?: email ?: ""
    val projectId: String? get() = prefs.getString(K_PROJECT, null)
    val projectName: String get() = prefs.getString(K_PROJECT_NAME, null) ?: ""
    val projectPrefix: String get() = prefs.getString(K_PREFIX, null) ?: ""
    val role: String get() = prefs.getString(K_ROLE, null) ?: "viewer"
    /** Edit existing records (everyone except viewer). */
    val canEdit: Boolean get() = role != "viewer"
    /** Add new tapstands / applicants (manager supervises but doesn't encode). */
    val canAdd: Boolean get() = role in setOf("admin", "co_admin", "office", "field")
    /** Office-level decisions (and can change approved applicants). */
    val isOffice: Boolean get() = role in setOf("admin", "co_admin", "manager", "office")
    val roleLabel: String get() = when (role) {
        "admin" -> "Admin"; "co_admin" -> "Co-admin"; "manager" -> "Manager"; "office" -> "Office"; "field" -> "Field"; else -> "Viewer"
    }

    fun saveTokens(access: String, refresh: String, expiresInSec: Long) {
        accessToken = access
        refreshToken = refresh
        expiresAt = System.currentTimeMillis() / 1000 + expiresInSec
    }

    fun saveUser(userId: String, email: String?) {
        prefs.edit().putString(K_USER, userId).putString(K_EMAIL, email).apply()
    }

    fun saveProfile(fullName: String?, projectId: String, projectName: String, prefix: String, role: String) {
        prefs.edit()
            .putString(K_NAME, fullName)
            .putString(K_PROJECT, projectId)
            .putString(K_PROJECT_NAME, projectName)
            .putString(K_PREFIX, prefix)
            .putString(K_ROLE, role)
            .apply()
        _loggedIn.value = true
    }

    fun clear() {
        prefs.edit().clear().apply()
        _loggedIn.value = false
    }

    private companion object {
        const val K_ACCESS = "access"; const val K_REFRESH = "refresh"; const val K_EXPIRES = "expires"
        const val K_USER = "user"; const val K_EMAIL = "email"; const val K_NAME = "name"
        const val K_PROJECT = "project"; const val K_PROJECT_NAME = "project_name"; const val K_PREFIX = "prefix"; const val K_ROLE = "role"
    }
}
