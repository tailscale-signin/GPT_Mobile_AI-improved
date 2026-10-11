package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navigation
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.collectReusableProfileLabels
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen
import dev.chungjungsoo.gptmobile.presentation.ui.home.HomeScreen
import dev.chungjungsoo.gptmobile.presentation.ui.mcp.McpMarketplaceScreen
import dev.chungjungsoo.gptmobile.presentation.ui.migrate.MigrateScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AboutScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AddPlatformScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AdvancedSettingsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AiPlatformsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.DebugDiagnosticsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.FactVaultScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.FactVaultViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LicenseScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalModelsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.McpToolsSelectionScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.OpenRouterSettingsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ProviderConnectionSettingsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingViewModelV2
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsTypographyScope
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionEditorScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupCompleteScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupPlatformListScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupPlatformTypeScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupPlatformWizardScreen
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupViewModelV2
import dev.chungjungsoo.gptmobile.presentation.ui.startscreen.StartScreen
import dev.chungjungsoo.gptmobile.presentation.viewmodel.OpenRouterSettingsViewModel

private const val PAGE_ENTER_DURATION_MS = 280
private const val PAGE_EXIT_DURATION_MS = 240
private const val PAGE_FADE_DURATION_MS = 180

@Composable
fun SetupNavGraph(
    navController: NavHostController,
    toolConnectionsViewModel: ToolConnectionsViewModel,
    onLaunchOAuth: (String) -> Unit = {}
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    dev.chungjungsoo.gptmobile.presentation.navigation.AppNavigationShell(navController, currentEntry?.destination?.route) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val navHost: @Composable () -> Unit = {
                    AppNavHost(navController, toolConnectionsViewModel, onLaunchOAuth)
                }
                if (isSettingsDestination(currentEntry?.destination?.route)) {
                    SettingsTypographyScope(content = navHost)
                } else {
                    navHost()
                }
            }
            BenchmarkExecutionBanner(
                Modifier.navigationBarsPadding(),
                onOpen = {
                    if (currentEntry?.destination?.route != Route.PROFILE_BENCHMARKS) navController.navigate(Route.PROFILE_BENCHMARKS.replace("{profileUid}", ""))
                }
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    toolConnectionsViewModel: ToolConnectionsViewModel,
    onLaunchOAuth: (String) -> Unit
) {
    val reducedMotion = LocalReducedMotion.current
    NavHost(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        navController = navController,
        startDestination = Route.CHAT_LIST,
        enterTransition = {
            slideInHorizontally(
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else PAGE_ENTER_DURATION_MS,
                    easing = FastOutSlowInEasing
                ),
                initialOffsetX = { width -> width / 9 }
            ) +
                fadeIn(animationSpec = tween(durationMillis = if (reducedMotion) 0 else PAGE_FADE_DURATION_MS)) +
                scaleIn(
                    initialScale = 0.992f,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else PAGE_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
        },
        exitTransition = {
            slideOutHorizontally(
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 0 else PAGE_EXIT_DURATION_MS,
                    easing = FastOutSlowInEasing
                ),
                targetOffsetX = { width -> -width / 28 }
            ) +
                fadeOut(animationSpec = tween(durationMillis = if (reducedMotion) 0 else PAGE_FADE_DURATION_MS)) +
                scaleOut(
                    targetScale = 0.996f,
                    animationSpec = tween(
                        durationMillis = if (reducedMotion) 0 else PAGE_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
        },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = if (reducedMotion) 0 else PAGE_FADE_DURATION_MS)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = if (reducedMotion) 0 else PAGE_FADE_DURATION_MS)) }
    ) {
        homeScreenNavigation(navController)
        migrationScreenNavigation(navController)
        startScreenNavigation(navController)
        setupNavigation(navController)
        settingNavigation(navController, toolConnectionsViewModel, onLaunchOAuth)
        chatScreenNavigation(navController)
    }
}

private fun isSettingsDestination(route: String?): Boolean {
    if (route == null) return false
    val baseRoute = route.substringBefore('?')
    return baseRoute in setOf(Route.ACTIVITY, Route.DELEGATION, Route.CONVERSATION_PREFERENCES, Route.PRIVACY_DATA, Route.FEATURE_CONTROLS) ||
        baseRoute == Route.APPEARANCE ||
        baseRoute == Route.SETTINGS ||
        route == Route.AI_PLATFORMS ||
        route == Route.ADD_PLATFORM ||
        route == Route.OPENROUTER_SETTINGS ||
        baseRoute == Route.FACT_VAULT ||
        baseRoute == Route.ADVANCED_SETTINGS ||
        route == Route.USAGE_STATISTICS ||
        baseRoute == Route.DEBUG_DIAGNOSTICS ||
        route == Route.ABOUT_PAGE ||
        route == Route.LICENSE ||
        route.startsWith(Route.LOCAL_MODELS) ||
        route.startsWith("profile_benchmarks") ||
        route.startsWith("tool_connections") ||
        route.startsWith("platform_settings/") ||
        route.startsWith("provider_settings/")
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
        StartScreen(
            onStartClick = { navController.navigate(Route.SETUP_ROUTE) },
            onRestored = {
                navController.navigate(Route.CHAT_LIST) { popUpTo(Route.GET_STARTED) { inclusive = true } }
            }
        )
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
        composable(route = Route.SETUP_LOCAL_MODELS) {
            LocalModelsScreen(
                onRuntimePreferences = { navController.navigate(Route.FEATURE_CONTROLS + "?category=LOCAL") },
                startInMarketplace = true,
                onOpenProfile = { uid -> navController.navigate(Route.PLATFORM_SETTINGS.replace("{platformUid}", uid)) },
                onNavigationClick = { navController.navigateUp() }
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
                    navController.popBackStack(Route.SETUP_PLATFORM_LIST, inclusive = false)
                },
                onBackAction = { navController.navigateUp() },
                onNavigateToLocalModels = { navController.navigate(Route.SETUP_LOCAL_MODELS) }
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
                    !platform.enabled && platform.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM
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
            settingOnClick = { navController.navigate(Route.ACTIVITY) },
            onExistingChatClick = { chatRoom, targetMessageId ->
                val targetSuffix = if (targetMessageId != null) "&targetMessageId=$targetMessageId" else ""
                navController.navigate(
                    "chat_room/${chatRoom.id}?enabled=${chatRoom.enabledPlatform.joinToString(",")}" +
                        "&mode=${chatRoom.conversationMode}$targetSuffix"
                )
            },
            navigateToNewChat = { enabledPlatforms, conversationMode ->
                navController.navigate(
                    "chat_room/0?enabled=${enabledPlatforms.joinToString(",")}&mode=$conversationMode"
                )
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
            navArgument("conversationMode") { defaultValue = ConversationMode.STANDARD },
            navArgument("incomingShare") { defaultValue = "" },
            navArgument("regenerate") {
                type = NavType.BoolType
                defaultValue = false
            },
            navArgument("targetMessageId") {
                type = NavType.IntType
                defaultValue = -1
            }
        )
    ) {
        val conversation: dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel = hiltViewModel()
        dev.chungjungsoo.gptmobile.presentation.ui.chat.AdaptiveConversationLayout(it.arguments?.getInt("chatRoomId") ?: 0, { id -> conversation.leaveConversation { navController.navigate("chat_room/$id") { popUpTo(Route.CHAT_ROOM) { inclusive = true } } } }) {
            ChatScreen(
                chatViewModel = conversation,
                onBackAction = { navController.navigateUp() },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS + "?marketplace=true") },
                onOpenConversation = { id, regenerate -> navController.navigate("chat_room/$id?regenerate=$regenerate") },
                onInspectContext = { id, run -> navController.navigate("workspaces?chatId=$id&tab=Context&runId=${android.net.Uri.encode(run)}") },
                onWorkspace = { id, tab -> navController.navigate("workspaces?chatId=$id&tab=$tab") }
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun NavGraphBuilder.settingNavigation(
    navController: NavHostController,
    toolConnectionsViewModel: ToolConnectionsViewModel,
    onLaunchOAuth: (String) -> Unit = {}
) {
    navigation(startDestination = Route.SETTINGS, route = Route.SETTING_ROUTE) {
        composable(Route.SETTINGS) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            val toolState by toolConnectionsViewModel.uiState.collectAsStateWithLifecycle()
            val githubConnection = toolState.connections.firstOrNull { connection ->
                (toolState.pluginStates[ToolPluginId.GITHUB] ?: true) &&
                    connection.type == ToolConnectionType.GITHUB &&
                    (toolState.pluginStates[ToolPluginId.connection(connection.connectionUid)] ?: true)
            }
            SettingScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onNavigateToAiPlatforms = { navController.navigate(Route.AI_PLATFORMS) },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS) },
                onNavigateToOpenRouterSettings = { navController.navigate(Route.OPENROUTER_SETTINGS) },
                onNavigateToToolConnections = { navController.navigate(Route.TOOL_CONNECTIONS) },
                onNavigateToAdvancedSettings = { navController.navigate(Route.ADVANCED_SETTINGS) },
                onNavigateToWorkspaces = { navController.navigate("workspaces") },
                githubWorkspaceEnabled = githubConnection != null,
                onNavigateToFactVault = { navController.navigate(Route.FACT_VAULT) },
                onNavigateToDebugDiagnostics = { navController.navigate(Route.ACTIVITY) },
                onNavigateToAboutPage = { navController.navigate(Route.ABOUT_PAGE) },
                onNavigateToAppearance = { navController.navigate(Route.APPEARANCE) },
                onSearchDestination = { destination, focus ->
                    val encoded = android.net.Uri.encode(focus)
                    val target = when (destination) {
                        "ADVANCED" -> if (focus.startsWith("privacy")) Route.PRIVACY_DATA + "?focus=$encoded" else Route.ADVANCED_SETTINGS + "?focus=$encoded"
                        "FEATURE_CONTROLS" -> Route.FEATURE_CONTROLS + "?category=${focus.substringBefore('|')}&focus=${android.net.Uri.encode(focus.substringAfter('|', ""))}"
                        "CONVERSATIONS" -> Route.CONVERSATION_PREFERENCES
                        "PRIVACY" -> Route.PRIVACY_DATA
                        "BACKUP" -> Route.PRIVACY_DATA + "?backup=true"
                        "DELEGATION" -> Route.DELEGATION
                        "APPEARANCE" -> Route.APPEARANCE + "?tab=$encoded"
                        "MEMORY" -> Route.FACT_VAULT + "?tab=$encoded"
                        "LOCAL_MODELS" -> Route.LOCAL_MODELS + "?runtime=${focus == "runtime"}"
                        "PROFILES" -> if (focus.isBlank()) {
                            Route.AI_PLATFORMS
                        } else {
                            val parts = focus.split('|', limit = 3)
                            Route.PLATFORM_SETTINGS.replace("{platformUid}", android.net.Uri.encode(parts[0])) + "?tab=${parts.getOrNull(1) ?: "0"}&focus=${android.net.Uri.encode(parts.getOrNull(2).orEmpty())}"
                        }
                        "TOOLS" -> Route.TOOL_CONNECTIONS
                        "DEBUG" -> Route.DEBUG_DIAGNOSTICS + "?focus=$encoded"
                        "STATISTICS" -> Route.USAGE_STATISTICS
                        "BENCHMARKS" -> Route.PROFILE_BENCHMARKS.replace("{profileUid}", "")
                        "WORKSPACE" -> "workspaces"
                        else -> Route.ABOUT_PAGE
                    }
                    navController.navigate(target)
                }
            )
        }
        composable(
            Route.WORKSPACES,
            arguments = listOf(
                navArgument("chatId") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("tab") { defaultValue = "GitHub" },
                navArgument("runId") { defaultValue = "" }
            )
        ) {
            val toolState by toolConnectionsViewModel.uiState.collectAsStateWithLifecycle()
            val githubConnection = toolState.connections.firstOrNull { connection ->
                (toolState.pluginStates[ToolPluginId.GITHUB] ?: true) &&
                    connection.type == ToolConnectionType.GITHUB &&
                    (toolState.pluginStates[ToolPluginId.connection(connection.connectionUid)] ?: true)
            }
            val workspaceTab = it.arguments?.getString("tab") ?: "GitHub"
            if (workspaceTab != "GitHub") {
                dev.chungjungsoo.gptmobile.presentation.ui.workspace.WorkspaceScreen(
                    onBack = { navController.navigateUp() },
                    onChat = { id -> navController.navigate("chat_room/$id") },
                    initialChat = it.arguments?.getInt("chatId") ?: 0,
                    initialTab = workspaceTab,
                    initialRun = it.arguments?.getString("runId").orEmpty(),
                    focused = true
                )
            } else if (githubConnection != null) {
                dev.chungjungsoo.gptmobile.presentation.ui.setting.GitHubWorkspaceScreen(
                    connection = githubConnection,
                    onDismiss = { navController.navigateUp() },
                    asDialog = false
                )
            } else {
                androidx.compose.material3.Scaffold(topBar = {
                    androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text("GitHub workspace") }, navigationIcon = {
                        androidx.compose.material3.TextButton(onClick = { navController.navigateUp() }) { androidx.compose.material3.Text("Back") }
                    })
                }) { padding ->
                    dev.chungjungsoo.gptmobile.presentation.designsystem.AppEmptyState(
                        "Connect and enable the GitHub API plugin to browse repositories and prepare changes.",
                        "Open Tools",
                        { navController.navigate(Route.TOOL_CONNECTIONS) },
                        Modifier.padding(padding)
                    )
                }
            }
        }
        composable(Route.ACTIVITY + "?tab={tab}", arguments = listOf(navArgument("tab") { defaultValue = "Runs" })) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(Route.SETTING_ROUTE) }
            val settings: SettingViewModelV2 = hiltViewModel(parentEntry)
            dev.chungjungsoo.gptmobile.presentation.ui.setting.ActivityScreen(
                settings,
                toolConnectionsViewModel,
                entry.arguments?.getString("tab") ?: "Runs",
                { navController.navigateUp() },
                { id -> navController.navigate("chat_room/$id") }
            )
        }
        composable(Route.DELEGATION) {
            dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalToolConfigurationDialog("delegation", { navController.navigateUp() }, asDialog = false)
        }
        composable(
            Route.FEATURE_CONTROLS + "?category={category}&focus={focus}",
            arguments = listOf(navArgument("category") { defaultValue = "CONVERSATIONS" }, navArgument("focus") { defaultValue = "" })
        ) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(Route.SETTING_ROUTE) }
            val category = dev.chungjungsoo.gptmobile.presentation.ui.setting.FeatureHome.entries.firstOrNull { it.name == entry.arguments?.getString("category") }
                ?: dev.chungjungsoo.gptmobile.presentation.ui.setting.FeatureHome.CONVERSATIONS
            AdvancedSettingsScreen(
                hiltViewModel<SettingViewModelV2>(parentEntry),
                { navController.navigateUp() },
                title = category.title,
                initialFocus = entry.arguments?.getString("focus").orEmpty(),
                featureHomeFilter = category,
                showPrivacy = false
            )
        }
        composable(Route.CONVERSATION_PREFERENCES) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(Route.SETTING_ROUTE) }
            AdvancedSettingsScreen(
                viewModel = hiltViewModel<SettingViewModelV2>(parentEntry),
                onNavigationClick = { navController.navigateUp() },
                title = "Conversations",
                featureHomeFilter = dev.chungjungsoo.gptmobile.presentation.ui.setting.FeatureHome.CONVERSATIONS,
                showPrivacy = false,
                onAllControls = { navController.navigate(Route.ADVANCED_SETTINGS) }
            )
        }
        composable(
            Route.PRIVACY_DATA + "?focus={focus}&backup={backup}",
            arguments = listOf(
                navArgument("focus") { defaultValue = "" },
                navArgument("backup") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(Route.SETTING_ROUTE) }
            dev.chungjungsoo.gptmobile.presentation.ui.setting.PrivacyDataScreen(
                hiltViewModel<SettingViewModelV2>(parentEntry),
                { navController.navigateUp() },
                entry.arguments?.getString("focus").orEmpty(),
                entry.arguments?.getBoolean("backup") == true
            )
        }
        composable(Route.OPENROUTER_SETTINGS) {
            val viewModel: OpenRouterSettingsViewModel = hiltViewModel()
            OpenRouterSettingsScreen(
                viewModel = viewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }

        composable(Route.FACT_VAULT + "?tab={tab}", arguments = listOf(navArgument("tab") { defaultValue = "Memories" })) { entry ->
            val viewModel: FactVaultViewModel = hiltViewModel()
            FactVaultScreen(viewModel, initialTab = entry.arguments?.getString("tab") ?: "Memories", onBack = { navController.navigateUp() }, onOpenConversation = { chatId, messageId ->
                navController.navigate("chat_room/$chatId?targetMessageId=${messageId ?: -1}")
            })
        }

        composable(Route.APPEARANCE + "?tab={tab}", arguments = listOf(navArgument("tab") { defaultValue = "0" })) { entry ->
            dev.chungjungsoo.gptmobile.presentation.ui.setting.ThemeSettingsScreen(
                onDismiss = { navController.navigateUp() },
                asDialog = false,
                initialTab = entry.arguments?.getString("tab")?.toIntOrNull() ?: 0,
                onDisplayPreferences = { navController.navigate(Route.FEATURE_CONTROLS + "?category=APPEARANCE") }
            )
        }
        composable(Route.ADVANCED_SETTINGS + "?focus={focus}", arguments = listOf(navArgument("focus") { defaultValue = "" })) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            AdvancedSettingsScreen(
                viewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                initialFocus = it.arguments?.getString("focus").orEmpty()
            )
        }
        composable(Route.USAGE_STATISTICS) {
            dev.chungjungsoo.gptmobile.presentation.ui.setting.UsageStatisticsScreen(onBack = { navController.navigateUp() })
        }
        composable(
            Route.PROFILE_BENCHMARKS,
            arguments = listOf(
                navArgument("profileUid") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) {
            dev.chungjungsoo.gptmobile.presentation.ui.setting.ProfileBenchmarkScreen(
                onBack = { navController.navigateUp() },
                onUsage = { navController.navigate(Route.USAGE_STATISTICS) }
            )
        }
        composable(Route.DEBUG_DIAGNOSTICS + "?focus={focus}", arguments = listOf(navArgument("focus") { defaultValue = "" })) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            DebugDiagnosticsScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onStatisticsClick = { navController.navigate(Route.USAGE_STATISTICS) },
                onBenchmarksClick = { navController.navigate(Route.PROFILE_BENCHMARKS.replace("{profileUid}", "")) },
                initialFocus = it.arguments?.getString("focus").orEmpty(),
                toolConnectionsViewModel = toolConnectionsViewModel
            )
        }
        composable(Route.AI_PLATFORMS) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            AiPlatformsScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS) },
                onDelegation = { navController.navigate(Route.DELEGATION) },
                onPreferences = { category -> navController.navigate(Route.FEATURE_CONTROLS + "?category=$category") },
                onWorkspace = { tab -> navController.navigate("workspaces?tab=$tab") },
                onActivity = { navController.navigate(Route.ACTIVITY) },
                onNavigateToAddPlatform = { navController.navigate(Route.ADD_PLATFORM) },
                onNavigateToOpenRouterSettings = { navController.navigate(Route.OPENROUTER_SETTINGS) },
                onNavigateToProviderSettings = { connectionUid ->
                    navController.navigate(
                        Route.PROVIDER_CONNECTION_SETTINGS.replace("{connectionUid}", connectionUid)
                    )
                },
                onNavigateToPlatformSetting = { platformUid ->
                    navController.navigate(
                        Route.PLATFORM_SETTINGS.replace("{platformUid}", platformUid)
                    )
                }
            )
        }
        composable(
            Route.PROVIDER_CONNECTION_SETTINGS,
            arguments = listOf(navArgument("connectionUid") { type = NavType.StringType })
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            ProviderConnectionSettingsScreen(
                connectionUid = backStackEntry.arguments?.getString("connectionUid").orEmpty(),
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.ADD_PLATFORM) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            val providerConnections by settingViewModel.providerConnections.collectAsStateWithLifecycle()
            val profiles by settingViewModel.platformState.collectAsStateWithLifecycle()
            val reusableLabels = remember(profiles) {
                collectReusableProfileLabels(profiles.map { it.labels })
            }
            AddPlatformScreen(
                onNavigationClick = { navController.navigateUp() },
                savedConnections = providerConnections,
                reusableLabels = reusableLabels,
                onSave = { platform, newConnection, credential ->
                    settingViewModel.addPlatform(platform, newConnection, credential)
                    navController.navigateUp()
                },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS + "?marketplace=true") }
            )
        }
        composable(
            Route.PLATFORM_SETTINGS + "?tab={tab}&focus={focus}",
            arguments = listOf(
                navArgument("platformUid") { type = NavType.StringType },
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("focus") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val platformUid = backStackEntry.arguments?.getString("platformUid") ?: ""
            val platformViewModel: PlatformSettingViewModel = hiltViewModel(backStackEntry)
            PlatformSettingScreen(
                settingViewModel = platformViewModel,
                initialTab = backStackEntry.arguments?.getInt("tab") ?: 0,
                initialFocus = backStackEntry.arguments?.getString("focus").orEmpty(),
                onNavigationClick = { navController.navigateUp() },
                onNavigateToBenchmarks = { navController.navigate(Route.PROFILE_BENCHMARKS.replace("{profileUid}", android.net.Uri.encode(platformUid))) },
                onNavigateToUsage = { navController.navigate(Route.USAGE_STATISTICS) },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS + "?marketplace=true") },
                onNavigateToMcpTools = {
                    navController.navigate(
                        Route.MCP_TOOLS_SELECTION.replace("{platformUid}", platformUid)
                    )
                }
            )
        }
        composable(
            Route.MCP_TOOLS_SELECTION,
            arguments = listOf(navArgument("platformUid") { type = NavType.StringType })
        ) { backStackEntry ->
            val platformUid = backStackEntry.arguments?.getString("platformUid") ?: ""
            val platformViewModel: PlatformSettingViewModel = hiltViewModel(
                remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry(Route.PLATFORM_SETTINGS.replace("{platformUid}", platformUid)) }.getOrElse { backStackEntry }
                }
            )
            McpToolsSelectionScreen(
                platformUid = platformUid,
                viewModel = platformViewModel,
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(
            Route.LOCAL_MODELS + "?marketplace={marketplace}&runtime={runtime}",
            arguments = listOf(
                navArgument("marketplace") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("runtime") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            LocalModelsScreen(
                onRuntimePreferences = { navController.navigate(Route.FEATURE_CONTROLS + "?category=LOCAL") },
                startInMarketplace = entry.arguments?.getBoolean("marketplace") == true,
                startInDelegation = entry.arguments?.getBoolean("runtime") == true,
                onOpenProfile = { uid -> navController.navigate(Route.PLATFORM_SETTINGS.replace("{platformUid}", uid)) },
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.TOOL_CONNECTIONS) {
            ToolConnectionsScreen(
                viewModel = toolConnectionsViewModel,
                onLaunchOAuth = onLaunchOAuth,
                onNavigationClick = { navController.navigateUp() },
                onActivity = { navController.navigate(Route.ACTIVITY) },
                onGitHub = { navController.navigate("workspaces?tab=GitHub") },
                onDelegation = { navController.navigate(Route.DELEGATION) },
                onPreferences = { category -> navController.navigate(Route.FEATURE_CONTROLS + "?category=$category") },
                onMemory = { navController.navigate(Route.FACT_VAULT) },
                onMarketplaceClick = { navController.navigate(Route.MCP_MARKETPLACE) },
                onAddConnectionClick = { navController.navigate(Route.ADD_TOOL_CONNECTION) },
                onEditConnectionClick = { connectionUid ->
                    navController.navigate(Route.EDIT_TOOL_CONNECTION.replace("{connectionUid}", connectionUid))
                }
            )
        }
        composable(Route.MCP_MARKETPLACE) { entry ->
            val settings: SettingViewModelV2 = hiltViewModel(remember(entry) { navController.getBackStackEntry(Route.SETTING_ROUTE) })
            val profiles by settings.platformState.collectAsStateWithLifecycle()
            var installedName by remember { mutableStateOf<String?>(null) }
            val uiState by toolConnectionsViewModel.uiState.collectAsStateWithLifecycle()
            val mcpProvider = remember {
                ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP }
            }
            McpMarketplaceScreen(
                onOpenDelegation = null,
                onNavigationClick = { navController.navigateUp() },
                onInstallPresetWithConfig = { preset, name, alias, endpoint, authType, credential, allowCleartext ->
                    toolConnectionsViewModel.saveConnection(
                        existing = null,
                        provider = mcpProvider,
                        name = name,
                        alias = alias,
                        endpointUrl = endpoint,
                        authType = authType,
                        credential = credential,
                        oauthClientId = "",
                        allowCleartext = allowCleartext,
                        clearCredential = false,
                        marketplaceProviderId = preset.id,
                        onSuccess = {
                            if (authType == dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType.OAUTH) {
                                navController.navigateUp()
                            } else {
                                installedName = name
                            }
                        }
                    )
                }
            )
            installedName?.let { name ->
                dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
                    onDismissRequest = { installedName = null },
                    title = { androidx.compose.material3.Text("$name saved") },
                    text = {
                        androidx.compose.foundation.lazy.LazyColumn {
                            item { androidx.compose.material3.Text(stringResource(R.string.ui_v2_choose_an_ai_profile_then_select_the_server_tools_it_may_use)) }
                            items(profiles.size) { index ->
                                val profile = profiles[index]
                                androidx.compose.material3.TextButton(onClick = {
                                    installedName = null
                                    navController.navigate(Route.MCP_TOOLS_SELECTION.replace("{platformUid}", profile.uid))
                                }) { androidx.compose.material3.Text(profile.name) }
                            }
                            if (profiles.isEmpty()) item { androidx.compose.material3.Text(stringResource(R.string.ui_v2_add_an_ai_profile_in_settings_to_enable_its_tools)) }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            installedName = null
                            navController.navigateUp()
                        }) { androidx.compose.material3.Text(stringResource(R.string.done)) }
                    }
                )
            }
            uiState.errorMessage?.let { message ->
                dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
                    onDismissRequest = toolConnectionsViewModel::clearError,
                    title = { androidx.compose.material3.Text(stringResource(R.string.ui_v2_could_not_install)) },
                    text = { androidx.compose.material3.Text(message) },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = toolConnectionsViewModel::clearError) { androidx.compose.material3.Text(stringResource(R.string.local_tools_settings_panel_label_12)) } }
                )
            }
        }
        composable(Route.ADD_TOOL_CONNECTION) {
            ToolConnectionEditorScreen(
                viewModel = toolConnectionsViewModel,
                onNavigationClick = { navController.navigateUp() },
                onSaveComplete = { navController.navigateUp() }
            )
        }
        composable(
            Route.EDIT_TOOL_CONNECTION,
            arguments = listOf(navArgument("connectionUid") { type = NavType.StringType })
        ) {
            ToolConnectionEditorScreen(
                connectionUid = it.arguments?.getString("connectionUid"),
                viewModel = toolConnectionsViewModel,
                onNavigationClick = { navController.navigateUp() },
                onSaveComplete = { navController.navigateUp() }
            )
        }
        composable(Route.ABOUT_PAGE) {
            AboutScreen(
                onNavigationClick = { navController.navigateUp() },
                onNavigationToLicense = { navController.navigate(Route.LICENSE) }
            )
        }
        composable(Route.LICENSE) {
            LicenseScreen(onNavigationClick = { navController.navigateUp() })
        }
    }
}
