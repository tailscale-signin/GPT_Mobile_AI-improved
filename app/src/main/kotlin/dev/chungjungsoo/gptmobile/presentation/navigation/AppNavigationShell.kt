package dev.chungjungsoo.gptmobile.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.chungjungsoo.gptmobile.presentation.common.Route

internal enum class AppRootDestination(val route: String, val label: String) {
    CHATS(Route.CHAT_LIST, "Chats"),
    AI(Route.AI_PLATFORMS, "AI"),
    TOOLS(Route.TOOL_CONNECTIONS, "Tools"),
    SETTINGS(Route.SETTINGS, "Settings")
}

internal fun rootDestinationForRoute(route: String?): AppRootDestination? =
    AppRootDestination.entries.firstOrNull { it.route == route?.substringBefore('?') }

internal fun showsCompactRootNavigation(destination: AppRootDestination?, rail: Boolean): Boolean =
    !rail && destination in setOf(AppRootDestination.AI, AppRootDestination.TOOLS)

private val AppRootDestination.icon: ImageVector
    get() = when (this) {
        AppRootDestination.CHATS -> Icons.AutoMirrored.Rounded.Chat
        AppRootDestination.AI -> Icons.Rounded.SmartToy
        AppRootDestination.TOOLS -> Icons.Rounded.GridView
        AppRootDestination.SETTINGS -> Icons.Rounded.Tune
    }

@Composable
internal fun AppNavigationShell(navController: NavHostController, route: String?, content: @Composable () -> Unit) {
    val selected = rootDestinationForRoute(route)
    val navigate: (AppRootDestination) -> Unit = { target ->
        if (target != selected) {
            navController.navigate(target.route) {
                popUpTo(Route.CHAT_LIST) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // The shell owns IME space; Compose consumption makes child imePadding a no-op.
    // Screens retain ownership of system bars through their Scaffold/top bars.
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val rail = selected != null && maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    AppRootDestination.entries.forEach { root ->
                        NavigationRailItem(selected = root == selected, onClick = { navigate(root) }, icon = { Icon(root.icon, null) }, label = { Text(root.label) })
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).then(if (showsCompactRootNavigation(selected, rail)) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)) { content() }
                if (showsCompactRootNavigation(selected, rail)) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        AppRootDestination.entries.forEach { root ->
                            NavigationBarItem(selected = root == selected, onClick = { navigate(root) }, icon = { Icon(root.icon, null) }, label = { Text(root.label) })
                        }
                    }
                }
            }
        }
    }
}
