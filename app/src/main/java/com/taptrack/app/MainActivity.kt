package com.taptrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.taptrack.app.ui.AppRoot
import com.taptrack.app.ui.components.AppUpdateDialog
import com.taptrack.app.ui.theme.TapTrackTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as TapTrackApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TapTrackTheme {
                AppRoot(container)
                AppUpdateDialog(container.updater)
            }
        }
    }

    /** Check for a new version whenever the app is opened / brought back (at most every 15 min). */
    override fun onResume() {
        super.onResume()
        container.updater.checkIfDue()
    }
}
