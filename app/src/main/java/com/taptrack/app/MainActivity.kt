package com.taptrack.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.taptrack.app.ui.AppRoot
import com.taptrack.app.ui.components.ForceUpdateDialog
import com.taptrack.app.ui.components.MaintenanceDialog
import com.taptrack.app.ui.components.UpdateAvailableDialog
import com.taptrack.app.ui.theme.TapTrackTheme
import com.taptrack.app.utils.AppUpdateState
import com.taptrack.app.utils.fetchAppControl
import com.taptrack.app.utils.resolveUpdateState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TapTrackApplication).container
        setContent {
            TapTrackTheme {
                var update by remember { mutableStateOf<AppUpdateState>(AppUpdateState.Normal) }
                LaunchedEffect(Unit) {
                    fetchAppControl(container.api)?.let { cfg ->
                        update = resolveUpdateState(cfg, BuildConfig.VERSION_CODE, getSharedPreferences("tt_update", Context.MODE_PRIVATE))
                    }
                }
                AppRoot(container)
                when (val u = update) {
                    is AppUpdateState.Maintenance -> MaintenanceDialog(u.message)
                    is AppUpdateState.ForceUpdate -> ForceUpdateDialog(onUpdate = { open(u.apkUrl) })
                    is AppUpdateState.UpdateAvailable -> UpdateAvailableDialog(onUpdate = { open(u.apkUrl) }, onDismiss = { update = AppUpdateState.Normal })
                    AppUpdateState.Normal -> {}
                }
            }
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
