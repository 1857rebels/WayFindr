package com.example.wayfindr.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.wayfindr.ui.WayfindrIcons
import com.example.wayfindr.ui.screens.*
import com.example.wayfindr.ui.theme.*
import com.example.wayfindr.viewmodel.AppViewModel
import com.example.wayfindr.viewmodel.ConnStage
import com.example.wayfindr.viewmodel.ConnectionState
import com.example.wayfindr.viewmodel.DashViewModel
import com.example.wayfindr.viewmodel.RouteViewModel

sealed class Screen(val route: String) {
    object Home     : Screen("home")
    object Route    : Screen("route")
    object Dash     : Screen("dash")
    object Garage   : Screen("garage")
    object Live     : Screen("live")
    object Rides    : Screen("rides")
    object Settings : Screen("settings")
}

private data class NavTab(val screen: Screen, val icon: ImageVector, val label: String)

private val bottomTabs = listOf(
    NavTab(Screen.Home,   WayfindrIcons.Home,    "Home"),
    NavTab(Screen.Route,  WayfindrIcons.Navi,    "Route"),
    NavTab(Screen.Dash,   WayfindrIcons.Dash,    "Dash"),
    NavTab(Screen.Live,   WayfindrIcons.Wrench,  "Live data"),
    NavTab(Screen.Rides,  WayfindrIcons.History, "Rides"),
)

@Composable
fun AppNavigation(
    appViewModel: AppViewModel = viewModel(),
    dashViewModel: DashViewModel = viewModel(),
    routeViewModel: RouteViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Navigate to a top-level destination with ONE consistent pattern — whether the tap came from
    // the bottom bar or a Home-screen button. The Home button used to do a plain navigate() while
    // the bottom bar used saveState/restoreState; mixing them corrupted the back stack so that,
    // after opening Dash from the Home button, tapping Home did nothing (target == start
    // destination + a restoreState mismatch). Routing both through this fixes it.
    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val showBottomNav = currentRoute in listOf(
        Screen.Home.route, Screen.Route.route, Screen.Dash.route,
        Screen.Garage.route, Screen.Live.route, Screen.Rides.route,
    )

    val garageTab by appViewModel.garageTab.collectAsState()
    val routeState by routeViewModel.state.collectAsState()

    // Single source of truth for connection status: the real dash stage. Fixes Home
    // claiming "Connected" while the Dash screen says it isn't.
    //
    // IMPORTANT: collect WITHOUT reading the state in the composable body. While streaming,
    // dashViewModel.ui updates ~15×/s (frame counter, rider position…). Reading it directly here
    // recomposed the entire nav scaffold 15×/s, which starved/janked the bottom-nav tabs — you
    // couldn't open Home while connected. derivedStateOf re-evaluates cheaply but only recomposes
    // readers when `conn` actually changes (i.e. when the STAGE changes).
    val dashUiState = dashViewModel.ui.collectAsState()
    val conn by remember {
        derivedStateOf {
            when (dashUiState.value.stage) {
                ConnStage.STREAMING -> ConnectionState.Connected
                ConnStage.WIFI, ConnStage.AUTH -> ConnectionState.Searching
                else -> ConnectionState.Offline
            }
        }
    }

    // Prefetch map tiles the moment a destination resolves (internet still reachable here)
    LaunchedEffect(routeState.destination?.lat, routeState.destination?.lng) {
        val d = routeState.destination
        if (d?.lat != null && d.lng != null) dashViewModel.prefetchTiles(d.lat, d.lng)
    }

    // Auto-navigate to Route when a Maps share arrives
    LaunchedEffect(routeState.pendingNavigate, currentRoute) {
        if (routeState.pendingNavigate && currentRoute != null) {
            if (currentRoute != Screen.Route.route) {
                navController.navigate(Screen.Route.route) { launchSingleTop = true }
            }
            routeViewModel.onNavigated()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        conn = conn,
                        onNavigate = { dest ->
                            when (dest) {
                                // Top-level destinations go through the shared tab navigation so the
                                // back stack stays consistent with the bottom bar (see navigateTab).
                                "route" -> navigateTab(Screen.Route.route)
                                "dash" -> navigateTab(Screen.Dash.route)
                                "garage" -> navigateTab(Screen.Live.route)
                                // Settings is a detail screen (its own back), not a tab.
                                "settings" -> navController.navigate(Screen.Settings.route)
                            }
                        },
                        routeViewModel = routeViewModel,
                    )
                }

                composable(Screen.Route.route) {
                    RouteScreen(
                        routeViewModel = routeViewModel,
                        onBack = { navController.popBackStack() },
                        onSentToDash = { stops ->
                            dashViewModel.setDestinations(stops)
                            // Start navigation: open the dash view and begin the
                            // WiFi → auth → stream flow (no-op if already streaming).
                            dashViewModel.connect()
                            navController.navigate(Screen.Dash.route) {
                                popUpTo(Screen.Home.route)
                            }
                        },
                    )
                }

                composable(Screen.Dash.route) {
                    DashScreen(vm = dashViewModel)
                }

                composable(Screen.Garage.route) {
                    GarageScreen(
                        tab = garageTab,
                        onTabChange = { appViewModel.setGarageTab(it) },
                    )
                }

                composable(Screen.Live.route) {
                    LiveDataScreen(vm = dashViewModel)
                }

                composable(Screen.Rides.route) {
                    RidesScreen()
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        conn = conn,
                        onConnChange = { appViewModel.setConn(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        // Checks GitHub Releases on app open; shows an update dialog only if a newer
        // version is published. Renders nothing otherwise.
        com.example.wayfindr.ui.components.UpdatePrompt()

        if (showBottomNav) {
            WayfindrBottomNav(
                currentRoute = currentRoute,
                onNavSelect = { screen -> navigateTab(screen.route) },
            )
        }
    }
}

@Composable
private fun WayfindrBottomNav(
    currentRoute: String?,
    onNavSelect: (Screen) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE60D0F11))
            .border(width = 1.dp, color = Line, shape = androidx.compose.ui.graphics.RectangleShape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .navigationBarsPadding(),
    ) {
        bottomTabs.forEach { tab ->
            val active = currentRoute == tab.screen.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onNavSelect(tab.screen) }
                    .padding(vertical = 6.dp),
            ) {
                // Fixed-height container with the icon top-aligned and the active dot
                // bottom-aligned — so reserving space for the dot doesn't shove the
                // selected icon downward relative to the others.
                Box(Modifier.height(29.dp), contentAlignment = Alignment.TopCenter) {
                    Icon(
                        tab.icon, contentDescription = tab.label,
                        tint = if (active) Gold else TextLo,
                        modifier = Modifier.size(23.dp),
                    )
                    if (active) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(Gold)
                        )
                    }
                }
                Text(
                    tab.label,
                    color = if (active) Gold else TextLo,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistFamily,
                )
            }
        }
    }
}
