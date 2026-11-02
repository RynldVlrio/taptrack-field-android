package com.taptrack.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taptrack.app.AppContainer
import com.taptrack.app.ui.screens.login.LoginScreen
import com.taptrack.app.ui.screens.map.MapScreen
import com.taptrack.app.ui.screens.more.MoreScreen
import com.taptrack.app.ui.screens.tapstands.TapstandEditScreen
import com.taptrack.app.ui.screens.tapstands.TapstandListScreen

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Suppress("DEPRECATION")
private val tabs = listOf(
    Tab("map", "Map", Icons.Default.Map),
    Tab("tapstands", "Tapstands", Icons.Default.List),
    Tab("more", "More", Icons.Default.MoreHoriz),
)

@Composable
fun AppRoot(container: AppContainer) {
    val loggedIn by container.session.loggedIn.collectAsStateWithLifecycle()
    val sync by container.sync.state.collectAsStateWithLifecycle()

    when {
        !loggedIn -> LoginScreen(container)
        sync.authExpired -> LoginScreen(container, reLogin = true)
        else -> MainShell(container)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainShell(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    fun editRoute(id: String? = null, lat: Double? = null, lng: Double? = null) =
        "tapstand_edit?id=${id ?: ""}&lat=${lat ?: ""}&lng=${lng ?: ""}"

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            NavHost(nav, startDestination = "map") {
                composable("map") {
                    MapScreen(container,
                        onOpenTapstand = { nav.navigate(editRoute(id = it)) },
                        onAddTapstand = { lat, lng -> nav.navigate(editRoute(lat = lat, lng = lng)) })
                }
                composable("tapstands") {
                    TapstandListScreen(container, onOpen = { nav.navigate(editRoute(id = it)) }, onAdd = { nav.navigate(editRoute()) })
                }
                composable("more") { MoreScreen(container) }
                composable(
                    "tapstand_edit?id={id}&lat={lat}&lng={lng}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("lng") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val a = entry.arguments
                    TapstandEditScreen(
                        container = container,
                        tapstandId = a?.getString("id")?.takeIf { it.isNotBlank() },
                        initialLat = a?.getString("lat")?.toDoubleOrNull(),
                        initialLng = a?.getString("lng")?.toDoubleOrNull(),
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
