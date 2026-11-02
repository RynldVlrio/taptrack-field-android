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

    /**
     * Sign in, find the user's project + role, then download the data.
     * If the same user signs back in (session expired), local unsynced changes are kept.
     */
    suspend fun signIn(email: String, password: String) {
        val previousUser = session.userId
        val user = api.signIn(email.trim(), password)
        val members = api.select("project_members?select=role,projects(id,name,prefix)&user_id=eq.${user.userId}")
        if (members.length() == 0) {
            session.clear()
            throw IllegalStateException("Your account is not assigned to any project yet. Ask the administrator.")
        }
        val m = members.getJSONObject(0)
        val project = m.getJSONObject("projects")
        val profile = api.select("profiles?select=full_name&id=eq.${user.userId}")
        val fullName = if (profile.length() > 0) profile.getJSONObject(0).optStringOrNull("full_name") else null

        if (previousUser != null && previousUser != user.userId) db.clearProjectData()   // different person on this phone
        session.saveUser(user.userId, user.email)
        session.saveProfile(fullName, project.getString("id"), project.getString("name"),
            project.optStringOrNull("prefix") ?: "", m.getString("role"))
        sync.clearAuthExpired()
        sync.syncNow()
    }

    /** Sign out and wipe this phone's copy of the data. Callers must warn about unsynced changes first. */
    suspend fun signOut() {
        db.clearProjectData()
        session.clear()
    }
}
