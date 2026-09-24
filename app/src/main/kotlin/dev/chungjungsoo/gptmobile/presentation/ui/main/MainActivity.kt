package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.agent.tool.MCP_OAUTH_SCHEME
import dev.chungjungsoo.gptmobile.data.agent.tool.isMcpOAuthCallbackUri
import dev.chungjungsoo.gptmobile.presentation.common.LocalDynamicTheme
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.Route
import dev.chungjungsoo.gptmobile.presentation.common.SetupNavGraph
import dev.chungjungsoo.gptmobile.presentation.common.ThemeSettingProvider
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val toolConnectionsViewModel: ToolConnectionsViewModel by viewModels()
    private lateinit var authTabLauncher: ActivityResultLauncher<Intent>
    private var lastOAuthCallback: String? = null
    private var lastOAuthLaunchUri: String? = null
    private var lastOAuthLaunchTime: Long = 0L
    private val pendingDeepLinkEvents = MutableSharedFlow<Intent>(replay = 1)

    @Volatile
    private var keepSplashOnScreen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().apply {
            setKeepOnScreenCondition { keepSplashOnScreen }
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
            dispatchOAuthCallback(result.resultUri?.toString())
        }

        // Prevent keyboard from pushing the entire view up - composable handles insets via imePadding()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        setContent {
            val navController = rememberNavController()
            val isReady by mainViewModel.isReady.collectAsStateWithLifecycle()

            ThemeSettingProvider {
                GPTMobileTheme(
                    dynamicTheme = LocalDynamicTheme.current,
                    themeMode = LocalThemeMode.current
                ) {
                    if (isReady) {
                        SetupNavGraph(
                            navController = navController,
                            toolConnectionsViewModel = toolConnectionsViewModel,
                            onLaunchOAuth = ::launchOAuth
                        )
                        LaunchedEffect(navController) {
                            navController.checkForExistingSettings()
                            navController.observeNotificationDeepLinks()
                            keepSplashOnScreen = false
                        }
                    }
                }
            }
        }
        dispatchOAuthIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchOAuthIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val hasChatRoom = intent.hasExtra(EXTRA_CHAT_ROOM_ID) || intent.hasExtra("conversation_id")
        if (hasChatRoom) {
            pendingDeepLinkEvents.tryEmit(intent)
        }
    }

    private fun NavHostController.observeNotificationDeepLinks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pendingDeepLinkEvents.collectLatest { intent ->
                    val chatRoomId = when {
                        intent.hasExtra(EXTRA_CHAT_ROOM_ID) -> intent.getIntExtra(EXTRA_CHAT_ROOM_ID, 0)
                        intent.hasExtra("conversation_id") -> intent.getStringExtra("conversation_id")?.toIntOrNull() ?: 0
                        else -> 0
                    }
                    val targetMessageId = when {
                        intent.hasExtra(EXTRA_TARGET_MESSAGE_ID) -> intent.getIntExtra(EXTRA_TARGET_MESSAGE_ID, -1)
                        intent.hasExtra("message_id") -> intent.getStringExtra("message_id")?.toIntOrNull() ?: -1
                        else -> -1
                    }

                    if (chatRoomId > 0) {
                        val route = if (targetMessageId > 0) {
                            "chat_room/$chatRoomId?targetMessageId=$targetMessageId"
                        } else {
                            "chat_room/$chatRoomId"
                        }
                        navigate(route) {
                            launchSingleTop = true
                        }
                    }
                }
            }
        }
    }

    private fun launchOAuth(authorizationUri: String) {
        val now = SystemClock.elapsedRealtime()
        // Deduplicate rapid duplicate launches of identical OAuth authorization URIs within 1 second
        if (authorizationUri == lastOAuthLaunchUri && (now - lastOAuthLaunchTime) < OAUTH_LAUNCH_DEBOUNCE_MS) {
            return
        }
        lastOAuthLaunchUri = authorizationUri
        lastOAuthLaunchTime = now

        val uri = Uri.parse(authorizationUri)
        try {
            AuthTabIntent.Builder().build().launch(authTabLauncher, uri, MCP_OAUTH_SCHEME)
        } catch (_: ActivityNotFoundException) {
            try {
                CustomTabsIntent.Builder().build().launchUrl(this, uri)
            } catch (_: ActivityNotFoundException) {
                toolConnectionsViewModel.failOAuthLaunch()
            }
        }
    }

    private fun dispatchOAuthCallback(callbackUri: String?) {
        if (callbackUri != null && callbackUri == lastOAuthCallback) return
        lastOAuthCallback = callbackUri
        toolConnectionsViewModel.completeOAuthCallback(callbackUri)
    }

    private fun dispatchOAuthIntent(intent: Intent?) = intent?.data?.toString()
        ?.takeIf(::isMcpOAuthCallbackUri)
        ?.let(::dispatchOAuthCallback)

    private fun NavHostController.checkForExistingSettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                mainViewModel.event.collect { event ->
                    when (event) {
                        MainViewModel.SplashEvent.OpenIntro -> {
                            navigate(Route.GET_STARTED) {
                                popUpTo(Route.CHAT_LIST) { inclusive = true }
                            }
                        }

                        MainViewModel.SplashEvent.OpenMigrate -> {
                            navigate(Route.MIGRATE_V2) {
                                popUpTo(Route.CHAT_LIST) { inclusive = true }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CHAT_ROOM_ID = "chatRoomId"
        const val EXTRA_TARGET_MESSAGE_ID = "targetMessageId"
        private const val OAUTH_LAUNCH_DEBOUNCE_MS = 1000L
    }
}