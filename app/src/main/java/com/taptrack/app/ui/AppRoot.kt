package com.taptrack.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.taptrack.app.ui.theme.Web
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taptrack.app.AppContainer
import com.taptrack.app.ui.screens.applicants.ApplicantEditScreen
import com.taptrack.app.ui.screens.applicants.ApplicantListScreen
import com.taptrack.app.ui.screens.login.LoginScreen
import com.taptrack.app.ui.screens.map.MapScreen
import com.taptrack.app.ui.screens.more.MoreScreen
import com.taptrack.app.ui.screens.tapstands.TapstandEditScreen
import com.taptrack.app.ui.screens.tapstands.TapstandListScreen

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("map", "Map", WebIcons.Map),
    Tab("tapstands", "Tapstands", WebIcons.Tapstands),
    Tab("applicants", "Applicants", WebIcons.Applicants),
    Tab("more", "Settings", WebIcons.Settings),
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

    fun appRoute(id: String? = null, lat: Double? = null, lng: Double? = null, ts: String? = null) =
        "applicant_edit?id=${id ?: ""}&lat=${lat ?: ""}&lng=${lng ?: ""}&ts=${ts ?: ""}"

    fun editRoute(id: String? = null, lat: Double? = null, lng: Double? = null) =
        "tapstand_edit?id=${id ?: ""}&lat=${lat ?: ""}&lng=${lng ?: ""}"

    Scaffold(
        bottomBar = {
            if (showBar) Column {
              HorizontalDivider(color = Web.Slate200)
              NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Web.Teal800, selectedTextColor = Web.Teal800, indicatorColor = Web.Teal50,
                            unselectedIconColor = Web.Slate600, unselectedTextColor = Web.Slate600,
                        ),
                    )
                }
              }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            NavHost(nav, startDestination = "map") {
                composable("map") {
                    MapScreen(container,
                        onOpenTapstand = { nav.navigate(editRoute(id = it)) },
                        onAddTapstand = { lat, lng -> nav.navigate(editRoute(lat = lat, lng = lng)) },
                        onOpenApplicant = { nav.navigate(appRoute(id = it)) },
                        onAddApplicant = { lat, lng, ts -> nav.navigate(appRoute(lat = lat, lng = lng, ts = ts)) })
                }
                composable("tapstands") {
                    TapstandListScreen(container, onOpen = { nav.navigate(editRoute(id = it)) }, onAdd = { nav.navigate(editRoute()) })
                }
                composable("applicants") {
                    ApplicantListScreen(container, onOpen = { nav.navigate(appRoute(id = it)) }, onAdd = { nav.navigate(appRoute()) })
                }
                composable("more") { MoreScreen(container) }
                composable(
                    "applicant_edit?id={id}&lat={lat}&lng={lng}&ts={ts}",
                    arguments = listOf("id", "lat", "lng", "ts").map { n -> navArgument(n) { type = NavType.StringType; nullable = true; defaultValue = null } },
                ) { entry ->
                    val a = entry.arguments
                    ApplicantEditScreen(
                        container = container,
                        applicantId = a?.getString("id")?.takeIf { it.isNotBlank() },
                        initialLat = a?.getString("lat")?.toDoubleOrNull(),
                        initialLng = a?.getString("lng")?.toDoubleOrNull(),
                        initialTapstandId = a?.getString("ts")?.takeIf { it.isNotBlank() },
                        onDone = { nav.popBackStack() },
                    )
                }
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
                        onOpenApplicant = { nav.navigate(appRoute(id = it)) },
                        onAddApplicant = { nav.navigate(appRoute(ts = it)) },
                    )
                }
            }
        }
    }
}
