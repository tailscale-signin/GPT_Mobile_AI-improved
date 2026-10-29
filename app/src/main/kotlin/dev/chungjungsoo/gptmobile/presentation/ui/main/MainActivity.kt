package dev.chungjungsoo.gptmobile.presentation.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.chungjungsoo.gptmobile.presentation.common.LocalCustomPrimaryArgb
import dev.chungjungsoo.gptmobile.presentation.common.LocalDynamicTheme
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.Route
import dev.chungjungsoo.gptmobile.presentation.common.SetupNavGraph
import dev.chungjungsoo.gptmobile.presentation.common.ThemeSettingProvider
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val toolConnectionsViewModel: ToolConnectionsViewModel by viewModels()
    private lateinit var authTabLauncher: ActivityResultLauncher<Intent>
    private var lastOAuthCallback: String? = null
    private var lastOAuthLaunchUri: String? = null
    private var lastOAuthLaunchTime: Long = 0L

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

        // Deliver IME insets to Compose; the navigation shell consumes keyboard space once.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContent {
            val navController = rememberNavController()
            val isReady by mainViewModel.isReady.collectAsStateWithLifecycle()
            val startupError by mainViewModel.startupError.collectAsStateWithLifecycle()
            LaunchedEffect(startupError) { if (startupError != null) keepSplashOnScreen = false }

            ThemeSettingProvider(enabled = isReady) {
                GPTMobileTheme(
                    dynamicTheme = LocalDynamicTheme.current,
                    themeMode = LocalThemeMode.current,
                    customPrimaryArgb = LocalCustomPrimaryArgb.current,
                    customPalette = dev.chungjungsoo.gptmobile.presentation.common.LocalCustomPalette.current
                ) {
                    if (startupError != null) {
                        StartupRecoveryScreen(requireNotNull(startupError), mainViewModel::retryStartup)
                    } else if (!isReady) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                    } else if (isReady) {
                        dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropHost {
                            SetupNavGraph(
                                navController = navController,
                                toolConnectionsViewModel = toolConnectionsViewModel,
                                onLaunchOAuth = ::launchOAuth
                            )
                        }
                        LaunchedEffect(navController) {
                            navController.checkForExistingSettings()
                            navController.observeNotificationDeepLinks()
                            keepSplashOnScreen = false
                            androidx.compose.runtime.withFrameNanos { }
                            if (mainViewModel.event.replayCache.lastOrNull() == MainViewModel.SplashEvent.OpenHome) dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.usableUi()
                        }
                    }
                }
            }
        }
        dispatchOAuthIntent(intent)
        if (savedInstanceState == null || intent?.hasExtra(EXTRA_NOTIFICATION_EVENT_ID) == true) handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchOAuthIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val eventId = intent.getStringExtra(EXTRA_NOTIFICATION_EVENT_ID)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: java.util.UUID.randomUUID().toString()
        if (intent.getBooleanExtra(dev.chungjungsoo.gptmobile.presentation.service.BenchmarkForegroundService.EXTRA_OPEN_BENCHMARKS, false)) {
            mainViewModel.enqueueNavigation(Route.PROFILE_BENCHMARKS.replace("{profileUid}", "all"), eventId)
            return
        }
        val chatId = if (intent.hasExtra(EXTRA_CHAT_ROOM_ID)) intent.getIntExtra(EXTRA_CHAT_ROOM_ID, 0) else intent.getStringExtra("conversation_id")?.toIntOrNull() ?: 0
        val messageId = if (intent.hasExtra(EXTRA_TARGET_MESSAGE_ID)) intent.getIntExtra(EXTRA_TARGET_MESSAGE_ID, -1) else intent.getStringExtra("message_id")?.toIntOrNull() ?: -1
        if (chatId <= 0) return
        var route = "chat_room/$chatId"
        if (messageId > 0) route += "?targetMessageId=$messageId"
        intent.getStringExtra("incomingShare")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { route += (if ('?' in route) "&" else "?") + "incomingShare=$it" }
        mainViewModel.enqueueNavigation(route, eventId)
    }

    private fun NavHostController.observeNotificationDeepLinks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                mainViewModel.pendingNavigation.collect { events ->
                    events.firstOrNull()?.let { event ->
                        navigate(event.substringAfter('|')) { launchSingleTop = true }
                        mainViewModel.consumeNavigation(event)
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
            if (dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthConfiguration.project().acceptsAuthorizationLaunch(authorizationUri)) {
                CustomTabsIntent.Builder().build().launchUrl(this, uri)
            } else {
                AuthTabIntent.Builder().build().launch(authTabLauncher, uri, MCP_OAUTH_SCHEME)
            }
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
        ?.takeIf { isMcpOAuthCallbackUri(it) || dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthConfiguration.project().acceptsCallback(it) }
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
        const val EXTRA_NOTIFICATION_EVENT_ID = "notificationEventId"
        const val EXTRA_CHAT_ROOM_ID = "chatRoomId"
        const val EXTRA_TARGET_MESSAGE_ID = "targetMessageId"
        private const val OAUTH_LAUNCH_DEBOUNCE_MS = 1000L
    }
}
