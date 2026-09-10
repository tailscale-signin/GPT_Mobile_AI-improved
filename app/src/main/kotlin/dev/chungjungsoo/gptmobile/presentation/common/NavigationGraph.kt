package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen
import dev.chungjungsoo.gptmobile.presentation.ui.home.HomeScreen
import dev.chungjungsoo.gptmobile.presentation.ui.mcp.McpMarketplaceScreen
import dev.chungjungsoo.gptmobile.presentation.ui.migrate.MigrateScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AboutScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AddPlatformScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LicenseScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalModelsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.McpToolsSelectionScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingViewModelV2
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionEditorScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupCompleteScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupPlatformListScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupPlatformTypeScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupPlatformWizardScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupViewModelV2
import dev.chungjungsoo.gptmobile.presentation.ui.startscreen.StartScreen

@Composable
fun SetupNavGraph(
    navController: NavHostController,
    toolConnectionsViewModel: ToolConnectionsViewModel,
    onLaunchOAuth: (String) -> Unit = {}
) {
    NavHost(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        navController = navController,
        startDestination = Route.CHAT_LIST
    ) {
        homeScreenNavigation(navController)
        migrationScreenNavigation(navController)
        startScreenNavigation(navController)
        setupNavigation(navController)
        settingNavigation(navController, toolConnectionsViewModel, onLaunchOAuth)
        chatScreenNavigation(navController)
    }
}

fun NavGraphBuilder.migrationScreenNavigation(navController: NavHostController) {
    composable(Route.MIGRATE_V2) {
        MigrateScreen {
            navController.navigate(Route.CHAT_LIST) {
                popUpTo(Route.MIGRATE_V2) { inclusive = true }
            }
        }
    }
}

fun NavGraphBuilder.startScreenNavigation(navController: NavHostController) {
    composable(Route.GET_STARTED) {
        StartScreen { navController.navigate(Route.SETUP_ROUTE) }
    }
}

fun NavGraphBuilder.setupNavigation(
    navController: NavHostController
) {
    navigation(startDestination = Route.SETUP_PLATFORM_LIST, route = Route.SETUP_ROUTE) {
        composable(route = Route.SETUP_PLATFORM_LIST) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformListScreen(
                setupViewModel = setupViewModel,
                onAddPlatform = { navController.navigate(Route.SETUP_PLATFORM_TYPE) },
                onComplete = { navController.navigate(Route.SETUP_COMPLETE) },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_PLATFORM_TYPE) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformTypeScreen(
                setupViewModel = setupViewModel,
                onPlatformTypeSelected = { navController.navigate(Route.SETUP_PLATFORM_WIZARD) },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_PLATFORM_WIZARD) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformWizardScreen(
                setupViewModel = setupViewModel,
                onComplete = {
                    // Go back to platform list after adding a platform
                    navController.popBackStack(Route.SETUP_PLATFORM_LIST, inclusive = false)
                },
                onBackAction = { navController.navigateUp() },
                onNavigateToLocalModels = { navController.navigate(Route.SETUP_LOCAL_MODELS) }
            )
        }
        composable(route = Route.SETUP_LOCAL_MODELS) {
            LocalModelsScreen(
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_COMPLETE) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            val platforms by setupViewModel.platforms.collectAsStateWithLifecycle()
            SetupCompleteScreen(
                isPendingLocalPlatform = platforms.any { platform ->
                    !platform.enabled && platform.compatibleType == ClientType.LITERT_LM
                },
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(Route.GET_STARTED) { inclusive = true }
                    }
                },
                onBackAction = { navController.navigateUp() }
            )
        }
    }
}

fun NavGraphBuilder.homeScreenNavigation(navController: NavHostController) {
    composable(Route.CHAT_LIST) {
        HomeScreen(
            settingOnClick = { navController.navigate(Route.SETTING_ROUTE) },
            onExistingChatClick = { chatRoom, targetMessageId ->
                val targetSuffix = if (targetMessageId != null) "&targetMessageId=$targetMessageId" else ""
                navController.navigate("chat_room/${chatRoom.id}?enabled=${chatRoom.enabledPlatform.joinToString(",")}$targetSuffix")
            },
            navigateToNewChat = { enabledPlatforms ->
                navController.navigate("chat_room/0?enabled=${enabledPlatforms.joinToString(",")}")
            }
        )
    }
}

fun NavGraphBuilder.chatScreenNavigation(navController: NavHostController) {
    composable(
        Route.CHAT_ROOM,
        arguments = listOf(
            navArgument("chatRoomId") { type = NavType.IntType },
            navArgument("enabledPlatforms") { defaultValue = "" },
            navArgument("targetMessageId") {
                type = NavType.IntType
                defaultValue = -1
            }
        )
    ) {
        ChatScreen(
            onBackAction = { navController.navigateUp() },
            onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS) }
        )
    }
}

fun NavGraphBuilder.settingNavigation(
    navController: NavHostController,
    toolConnectionsViewModel: ToolConnectionsViewModel,
    onLaunchOAuth: (String) -> Unit
) {
    navigation(startDestination = Route.SETTING, route = Route.SETTING_ROUTE) {
        composable(Route.SETTING) {
            val settingViewModel: SettingViewModelV2 = hiltViewModel()
            SettingScreen(
                viewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onAboutClick = { navController.navigate(Route.ABOUT) },
                onAddPlatformClick = { navController.navigate(Route.ADD_PLATFORM) },
                onToolConnectionsClick = { navController.navigate(Route.TOOL_CONNECTIONS) },
                onMcpMarketplaceClick = { navController.navigate(Route.MCP_MARKETPLACE) },
                onLocalModelsClick = { navController.navigate(Route.LOCAL_MODELS) },
                onPlatformItemClick = { platformUid ->
                    navController.navigate("platform_setting/$platformUid")
                }
            )
        }

        composable(Route.ABOUT) {
            AboutScreen(
                onNavigationClick = { navController.navigateUp() },
                onLicenseClick = { navController.navigate(Route.LICENSE) }
            )
        }

        composable(Route.LICENSE) {
            LicenseScreen(
                onNavigationClick = { navController.navigateUp() }
            )
        }

        composable(Route.ADD_PLATFORM) {
            val settingViewModel: SettingViewModelV2 = hiltViewModel()
            AddPlatformScreen(
                viewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onPlatformAdded = { platformUid ->
                    navController.navigate("platform_setting/$platformUid") {
                        popUpTo(Route.ADD_PLATFORM) { inclusive = true }
                    }
                },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS) }
            )
        }

        composable(Route.TOOL_CONNECTIONS) {
            ToolConnectionsScreen(
                viewModel = toolConnectionsViewModel,
                onNavigationClick = { navController.navigateUp() },
                onAddConnection = {
                    navController.navigate("tool_connection_editor/-1")
                },
                onEditConnection = { connectionId ->
                    navController.navigate("tool_connection_editor/$connectionId")
                },
                onExploreMarketplace = {
                    navController.navigate(Route.MCP_MARKETPLACE)
                },
                onLaunchOAuth = onLaunchOAuth
            )
        }

        composable(
            route = "tool_connection_editor/{connectionId}",
            arguments = listOf(
                navArgument("connectionId") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getInt("connectionId") ?: -1
            ToolConnectionEditorScreen(
                viewModel = toolConnectionsViewModel,
                connectionId = connectionId,
                onNavigationClick = { navController.navigateUp() },
                onSaved = { navController.navigateUp() },
                onLaunchOAuth = onLaunchOAuth
            )
        }

        composable(Route.MCP_MARKETPLACE) {
            McpMarketplaceScreen(
                toolConnectionsViewModel = toolConnectionsViewModel,
                onNavigationClick = { navController.navigateUp() },
                onInstallServer = { catalogServer ->
                    val initialName = catalogServer.name
                    val initialType = ToolConnectionType.MCP_STDIO.name
                    val initialConfig = catalogServer.suggestedCommand.orEmpty()
                    navController.navigate(
                        "tool_connection_editor/-1?name=$initialName&type=$initialType&config=${java.net.URLEncoder.encode(initialConfig, "UTF-8")}"
                    )
                }
            )
        }

        composable(Route.LOCAL_MODELS) {
            LocalModelsScreen(
                onNavigationClick = { navController.navigateUp() }
            )
        }

        composable(
            Route.PLATFORM_SETTING,
            arguments = listOf(
                navArgument("platformUid") { type = NavType.StringType }
            )
        ) {
            val platformViewModel: PlatformSettingViewModel = hiltViewModel()
            PlatformSettingScreen(
                viewModel = platformViewModel,
                onNavigationClick = { navController.navigateUp() },
                onConfigureMcpToolsClick = { platformUid ->
                    navController.navigate("mcp_tools_selection/$platformUid")
                },
                onConfigureConnectionsClick = {
                    navController.navigate(Route.TOOL_CONNECTIONS)
                },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS) }
            )
        }

        composable(
            Route.MCP_TOOLS_SELECTION,
            arguments = listOf(
                navArgument("platformUid") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val platformUid = backStackEntry.arguments?.getString("platformUid").orEmpty()
            val platformViewModel: PlatformSettingViewModel = hiltViewModel()
            McpToolsSelectionScreen(
                platformUid = platformUid,
                viewModel = platformViewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }
    }
}
