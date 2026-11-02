package com.taptrack.app.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.taptrack.app.BuildConfig
import com.taptrack.app.data.remote.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates. The office publishes a signed APK on the web (Settings → Mobile app release), which writes
 * the `app_config` keys below and puts the APK in the public "releases" bucket:
 *   status (ACTIVE / MAINTENANCE), message, latestVersionCode, latestVersionName, minSupportedVersionCode,
 *   apkUrl, apkSize, releaseNotes, graceDays
 * The app checks when it is opened (at most every 15 min), downloads the APK itself and hands it to Android's installer.
 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val sizeBytes: Long,
    val required: Boolean,
    val daysLeft: Int?,      // optional update that becomes required in N days
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()                       // only reported after a manual check
    data class Maintenance(val message: String) : UpdateState()
    data class Available(val info: ReleaseInfo) : UpdateState()
    data class Downloading(val info: ReleaseInfo, val done: Long, val total: Long) : UpdateState()
    data class ReadyToInstall(val info: ReleaseInfo, val file: File) : UpdateState()
    data class Failed(val info: ReleaseInfo?, val message: String) : UpdateState()
}

class AppUpdater(private val app: Application, private val api: SupabaseClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = app.getSharedPreferences("tt_update", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private var lastCheck = 0L
    private var dismissedCode = 0      // "Later" pressed for this version (until the app is restarted)
    private var downloadJob: Job? = null

    val currentCode: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    /** Called when the app comes to the foreground. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - lastCheck < 15 * 60_000) return
        scope.launch { check(manual = false) }
    }

    /** Check again now (ignores the 15-minute pause). */
    fun recheck() { scope.launch { check(manual = false) } }

    suspend fun check(manual: Boolean) {
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.ReadyToInstall) return
        if (manual) _state.value = UpdateState.Checking
        val cfg = try {
            val rows = api.selectAnon("app_config?select=key,value")
            (0 until rows.length()).associate { rows.getJSONObject(it).getString("key") to rows.getJSONObject(it).getString("value") }
        } catch (e: Exception) {
            if (manual) _state.value = UpdateState.Failed(null, "Can't check right now (no internet?)")
            return
        }
        lastCheck = System.currentTimeMillis()

        if ((cfg["status"] ?: "ACTIVE").uppercase() != "ACTIVE") {
            _state.value = UpdateState.Maintenance(cfg["message"].orEmpty()); return
        }
        val latest = cfg["latestVersionCode"]?.toIntOrNull() ?: 0
        val url = cfg["apkUrl"].orEmpty()
        if (url.isBlank() || latest <= currentCode) {
            prefs.edit().remove(KEY_FIRST_SEEN).apply()
            _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
            return
        }
        // Optional update becomes required after the grace period
        val graceDays = cfg["graceDays"]?.toIntOrNull() ?: 7
        val seenKey = "$KEY_FIRST_SEEN.$latest"
        val firstSeen = prefs.getLong(seenKey, 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs.edit().putLong(seenKey, it).apply() }
        val daysPassed = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - firstSeen).toInt()
        val belowMin = currentCode < (cfg["minSupportedVersionCode"]?.toIntOrNull() ?: 0)
        val required = belowMin || daysPassed >= graceDays
        val info = ReleaseInfo(
            versionCode = latest, versionName = cfg["latestVersionName"].orEmpty().ifBlank { "build $latest" }, url = url,
            notes = cfg["releaseNotes"].orEmpty(), sizeBytes = cfg["apkSize"]?.toLongOrNull() ?: 0L,
            required = required, daysLeft = if (required) null else (graceDays - daysPassed),
        )
        // Already downloaded earlier?
        val cached = apkFile(latest)
        if (cached.exists() && isValidApk(cached, latest)) { _state.value = UpdateState.ReadyToInstall(info, cached); return }
        if (!manual && !required && dismissedCode == latest) { _state.value = UpdateState.Idle; return }
        _state.value = UpdateState.Available(info)
    }

    fun later() {
        when (val s = _state.value) {
            is UpdateState.Available -> if (!s.info.required) { dismissedCode = s.info.versionCode; _state.value = UpdateState.Idle }
            is UpdateState.ReadyToInstall -> if (!s.info.required) { dismissedCode = s.info.versionCode; _state.value = UpdateState.Idle }
            is UpdateState.Failed -> if (s.info?.required != true) _state.value = UpdateState.Idle
            is UpdateState.UpToDate, is UpdateState.Checking -> _state.value = UpdateState.Idle
            else -> {}
        }
    }

    fun download(info: ReleaseInfo) {
        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            val target = apkFile(info.versionCode)
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                target.parentFile?.listFiles()?.forEach { if (it.name != target.name) it.delete() }   // old downloads
                _state.value = UpdateState.Downloading(info, 0, info.sizeBytes)
                http.newCall(Request.Builder().url(info.url).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code})")
                    val body = res.body ?: throw IllegalStateException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var lastEmit = 0L
                            while (isActive) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (done - lastEmit > 256 * 1024) { lastEmit = done; _state.value = UpdateState.Downloading(info, done, total) }
                            }
                        }
                    }
                }
                if (!isActive) { tmp.delete(); return@launch }
                if (!tmp.renameTo(target)) throw IllegalStateException("Could not save the update")
                if (!isValidApk(target, info.versionCode)) { target.delete(); throw IllegalStateException("The downloaded file is not a valid TapTrack update") }
                _state.value = UpdateState.ReadyToInstall(info, target)
            } catch (e: Exception) {
                tmp.delete()
                _state.value = UpdateState.Failed(info, e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        val s = _state.value
        if (s is UpdateState.Downloading) _state.value = UpdateState.Available(s.info)
    }

    /** True if Android must first be told to allow installs from TapTrack. */
    fun needsInstallPermission(): Boolean = !app.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${app.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Same app, the expected (newer) version code. Android itself also checks the signature on install. */
    private fun isValidApk(file: File, expectedCode: Int): Boolean = try {
        @Suppress("DEPRECATION")
        val pi = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        @Suppress("DEPRECATION")
        val code = pi?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode } ?: -1
        pi?.packageName == app.packageName && code == expectedCode
    } catch (_: Exception) { false }

    private fun apkFile(code: Int) = File(File(app.cacheDir, "updates").apply { mkdirs() }, "taptrack-$code.apk")

    private companion object { const val KEY_FIRST_SEEN = "first_seen" }
}

