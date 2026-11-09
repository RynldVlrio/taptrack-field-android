package com.taptrack.app

import android.app.Application
import com.taptrack.app.data.local.LocalDb
import com.taptrack.app.data.remote.SessionStore
import com.taptrack.app.data.remote.SupabaseClient
import com.taptrack.app.data.remote.optStringOrNull
import com.taptrack.app.data.repository.BoundaryRepository
import com.taptrack.app.data.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TapTrackApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        container = AppContainer(this)
        container.sync.startAutoSync()
    }
}

/** Single place that creates and holds the app's services. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = SessionStore(app)
    val api = SupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, session)
    val db = LocalDb.create(app)
    val sync = SyncManager(db, api, session, scope, java.io.File(app.filesDir, "layers"))
    val boundaryRepository = BoundaryRepository(db.boundaryDao())
    val updater = com.taptrack.app.utils.AppUpdater(app, api, scope)

    init {
        // As-Built surveys saved on this phone upload with every sync (offline queue, never twice)
        sync.extraUploads = { com.taptrack.app.utils.KmlExporter.uploadPending(app, this) }
    }

    /** Extra steps between "signed in" and "using the app" (shown instead of the login screen). */
    sealed class Onboarding {
        data class NeedsPassword(val email: String) : Onboarding()
        data class Pending(val email: String) : Onboarding()
    }
    val onboarding = MutableStateFlow<Onboarding?>(null)

    private var priorUser: String? = null   // who used this phone before (for installs older than "owner_user")

    /** Sign in with email + password (existing accounts). */
    suspend fun signIn(email: String, password: String) {
        priorUser = session.userId
        val user = api.signIn(email.trim(), password)
        finishSignIn(user.userId, user.email)
    }

    /** Email a one-time code: open an account (createUser) or forgot password. */
    suspend fun sendCode(email: String, createUser: Boolean, fullName: String? = null) =
        api.sendEmailCode(email.trim().lowercase(), createUser, fullName)

    /** Check the code; afterwards the person sets a password (see [setPassword]). */
    suspend fun verifyCode(email: String, code: String): String {
        priorUser = session.userId
        val user = api.verifyEmailCode(email.trim().lowercase(), code.trim())
        session.saveUser(user.userId, user.email)
        return user.userId
    }

    /** Request access to the project of an invite code. Returns "member", "pending" or "rejected". */
    suspend fun requestJoin(code: String, fullName: String, note: String?, newUser: Boolean): String =
        api.rpc("request_join", JSONObject().put("p_code", code.trim().uppercase()).put("p_full_name", fullName.trim())
            .put("p_note", note?.trim()?.ifEmpty { null } ?: JSONObject.NULL).put("p_new_user", newUser)).trim('"')

    /** Project name for an invite code (null if not valid). Works before sign-in. */
    suspend fun joinInfo(code: String): String? {
        val arr = JSONArray(api.rpcAnon("join_info", JSONObject().put("p_code", code.trim().uppercase())))
        return if (arr.length() > 0) arr.getJSONObject(0).optStringOrNull("project_name") else null
    }

    /** Own pending / rejected requests (for the waiting screen). */
    suspend fun myJoinRequests(): List<Pair<String, String>> {
        val arr = JSONArray(api.rpc("my_join_requests"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("project_name") to o.getString("status") } }
    }

    /** Save the person's own password, then continue into the app (or the waiting screen). */
    suspend fun setPassword(password: String) {
        api.updatePassword(password)
        api.rpc("mark_password_set")
        val uid = session.userId ?: throw IllegalStateException("Not signed in")
        finishSignIn(uid, session.email)
    }

    /** "Check again" on the waiting screen. */
    suspend fun recheckAccess() {
        val uid = session.userId ?: return
        finishSignIn(uid, session.email)
    }

    fun cancelOnboarding() { onboarding.value = null; session.clear() }

    /**
     * After any successful authentication: must the person choose a password? Are they in a project yet?
     * Local data is kept only if it belongs to this same user (e.g. signing back in after the session expired).
     */
    private suspend fun finishSignIn(userId: String, email: String?) {
        session.saveUser(userId, email)
        val profile = api.select("profiles?select=full_name,password_set&id=eq.$userId")
        val p = if (profile.length() > 0) profile.getJSONObject(0) else null
        if (p != null && !p.optBoolean("password_set", true)) { onboarding.value = Onboarding.NeedsPassword(email ?: ""); return }

        val members = api.select("project_members?select=role,projects(id,name,prefix)&user_id=eq.$userId")
        if (members.length() == 0) { onboarding.value = Onboarding.Pending(email ?: ""); return }
        val m = members.getJSONObject(0)
        val project = m.getJSONObject("projects")
        val owner = db.syncMeta().get("owner_user") ?: priorUser
        if (owner != null && owner != userId) db.clearProjectData()   // different person on this phone
        db.syncMeta().put(com.taptrack.app.data.local.SyncMetaEntity("owner_user", userId))
        session.saveProfile(p?.optStringOrNull("full_name"), project.getString("id"), project.getString("name"),
            project.optStringOrNull("prefix") ?: "", m.getString("role"))
        onboarding.value = null
        sync.clearAuthExpired()
        sync.syncNow()
    }

    /** Sign out and wipe this phone's copy of the data. Callers must warn about unsynced changes first. */
    suspend fun signOut() {
        db.clearProjectData()
        session.clear()
        onboarding.value = null
    }
}
