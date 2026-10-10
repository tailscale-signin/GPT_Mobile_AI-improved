package dev.chungjungsoo.gptmobile.presentation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.AppForegroundTracker
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AgentRunForegroundService : Service() {

    @Inject
    lateinit var coordinatorProvider: Lazy<AgentRunCoordinator>
    private val agentRunCoordinator get() = coordinatorProvider.get()

    @Inject
    lateinit var settingsProvider: Lazy<SettingRepository>
    private val settingRepository get() = settingsProvider.get()

    @Inject
    lateinit var chatsProvider: Lazy<ChatRepository>
    private val chatRepository get() = chatsProvider.get()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeRunsJob: Job? = null
    private var idleStopJob: Job? = null
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wasActive = false
    private var isForeground = false
    private var lastActiveProfileUid: String? = null
    private var lastActiveChatId: Int? = null
    private var lastAssistantMessageId: Int? = null
    private var lastNotificationUpdateTime = 0L
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        // Lazy injection keeps databases/model initialization out of the promotion deadline.
        AppLogRecorder.record("AgentService", "SERVICE_CREATED · elapsedMs=${SystemClock.elapsedRealtime()}")
        createNotificationChannel()
        updateNotification(emptyList())
        acquireWakeLock()
    }

    private fun observeActiveRuns() {
        activeRunsJob = serviceScope.launch {
            agentRunCoordinator.activeRuns
                .map { runsMap ->
                    val runs = runsMap.values.toList()
                    val summaries = runs.map {
                        ActiveRunSummary(
                            runId = it.runId,
                            profileUid = it.profileUid,
                            phase = it.phase,
                            gatewayStage = it.gatewayStage,
                            gatewayMessage = it.gatewayMessage,
                            gatewayCheckpoint = it.gatewayCheckpoint
                        )
                    }
                    val active = runs.isNotEmpty()
                    Triple(active, summaries, runs)
                }
                .distinctUntilChanged { old, new ->
                    old.first == new.first && old.second == new.second
                }
                .collect { (isActive, _, runs) ->
                    if (isActive) {
                        idleStopJob?.cancel()
                        runs.firstOrNull()?.let {
                            lastActiveProfileUid = it.profileUid
                            lastActiveChatId = it.chatId
                            lastAssistantMessageId = it.assistantMessageId
                        }
                        updateNotification(runs)
                        wasActive = true
                    } else {
                        scheduleIdleStop()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        idleStopJob?.cancel()
        // Re-promote an existing instance if a previous run was stopping.
        updateNotification(emptyList())
        AppLogRecorder.record("AgentService", "START_DELIVERED · startId=$startId · elapsedMs=${SystemClock.elapsedRealtime()}")
        if (activeRunsJob == null) observeActiveRuns()
        if (intent?.action == ACTION_CANCEL_ALL) {
            agentRunCoordinator.cancelAll()
            scheduleIdleStop()
            return START_NOT_STICKY
        }
        if (agentRunCoordinator.activeRuns.value.isEmpty()) scheduleIdleStop()
        return START_NOT_STICKY
    }

    private fun scheduleIdleStop() {
        idleStopJob?.cancel()
        val startId = latestStartId
        idleStopJob = serviceScope.launch {
            // Let a queued restart publish its active run before deciding to stop.
            delay(500)
            if (startId != latestStartId || agentRunCoordinator.activeRuns.value.isNotEmpty()) return@launch
            if (shouldNotifyAgentRunsCompleted(wasActive, false, AppForegroundTracker.isBackgrounded)) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { showCompletionNotification() }
            }
            if (startId != latestStartId || agentRunCoordinator.activeRuns.value.isNotEmpty()) return@launch
            // stopSelfResult protects a later start; do not remove foreground status first.
            if (stopSelfResult(startId)) {
                ServiceCompat.stopForeground(this@AgentRunForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                isForeground = false
                AppLogRecorder.record("AgentService", "STOPPED · startId=$startId · elapsedMs=${SystemClock.elapsedRealtime()}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeRunsJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun updateNotification(activeRuns: List<ActiveAgentRun>) {
        val contentText = resolveNotificationContentText(this, activeRuns)
        val now = SystemClock.uptimeMillis()

        // Batch notifications: suppress identical text within throttle window to save battery
        if (isForeground && contentText == lastNotificationText && (now - lastNotificationUpdateTime < NOTIFICATION_THROTTLE_MS)) {
            return
        }

        lastNotificationText = contentText
        lastNotificationUpdateTime = now

        val notification = buildNotification(contentText, activeRuns)
        if (!isForeground) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            isForeground = true
            AppLogRecorder.record("AgentService", "FOREGROUND_ENTERED · elapsedMs=${SystemClock.elapsedRealtime()}")
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String, activeRuns: List<ActiveAgentRun>): Notification {
        // Use the new fancy AI notification icon
        val openApp = buildOpenAppPendingIntent(1, lastActiveChatId)

        val cancelIntent = Intent(this, AgentRunForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelRuns = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(R.string.cancel_agent_runs), cancelRuns)

        val run = activeRuns.singleOrNull()
        val gatewayProgress = run?.let(::resolveGatewayProgressPercent)

        if (Build.VERSION.SDK_INT >= 36 && run != null) {
            val progressStyle = NotificationCompat.ProgressStyle()
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(20))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(20))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(20))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(20))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(20))

            if (gatewayProgress != null) {
                progressStyle.setProgress(gatewayProgress)
            } else {
                progressStyle.setProgressIndeterminate(true)
            }
            builder.setStyle(progressStyle)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private suspend fun showCompletionNotification() {
        val features = runCatching { settingRepository.getFeatureSettings() }.getOrNull()
        if (features?.responseNotifications == false) return

        val chatId = lastActiveChatId
        val targetMessageId = lastAssistantMessageId
        val platformName = lastActiveProfileUid?.let { uid ->
            settingRepository.fetchPlatformV2s().firstOrNull { it.uid == uid }?.name
        }
        val room = chatId?.takeIf { it > 0 }?.let { id ->
            (chatRepository.fetchChatListV2() + chatRepository.fetchArchivedChatListV2())
                .firstOrNull { it.id == id }
        }
        val completedMessage = if (chatId != null && chatId > 0) {
            chatRepository.fetchMessagesV2(chatId)
                .firstOrNull { it.id == targetMessageId }
                ?: chatRepository.fetchMessagesV2(chatId)
                    .lastOrNull { it.platformType != null && it.content.isNotBlank() }
        } else {
            null
        }

        val title = room?.title?.takeIf { it.isNotBlank() }
            ?: platformName?.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.agent_completion_platform_title, it) }
            ?: getString(R.string.agent_completion_notification_title)

        val preview = completedMessage?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(360)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.agent_completion_notification_text)

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            COMPLETION_NOTIFICATION_BASE_ID + (chatId ?: 0).coerceAtLeast(0),
            buildCompletionNotification(title, preview, chatId, completedMessage?.id ?: targetMessageId)
        )
        triggerCompletionVibration()
    }

    private fun triggerCompletionVibration() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let { vib ->
                if (vib.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Double pulse pattern: wait 0ms, buzz 150ms, wait 100ms, buzz 250ms
                        val timings = longArrayOf(0, 150, 100, 250)
                        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                        vib.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(longArrayOf(0, 150, 100, 250), -1)
                    }
                }
            }
        }
    }

    private fun buildCompletionNotification(
        title: String,
        preview: String,
        chatId: Int?,
        targetMessageId: Int?
    ): Notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_ai_notification)
        .setContentTitle(title)
        .setContentText(preview)
        .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
        .setSubText("AI response complete")
        .setContentIntent(buildOpenAppPendingIntent(2, chatId, targetMessageId))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .build()

    private fun buildOpenAppPendingIntent(
        requestCode: Int,
        chatId: Int? = null,
        targetMessageId: Int? = null
    ): PendingIntent {
        val openAppIntent = Intent().setClass(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (chatId != null && chatId > 0) {
                putExtra(MainActivity.EXTRA_CHAT_ROOM_ID, chatId)
            }
            if (targetMessageId != null && targetMessageId > 0) {
                putExtra(MainActivity.EXTRA_TARGET_MESSAGE_ID, targetMessageId)
            }
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager?.createNotificationChannel(
            NotificationChannel(
                COMPLETION_CHANNEL_ID,
                getString(R.string.agent_completion_notification_title),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.agent_completion_notification_text)
                enableVibration(true)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "agent_runs"
        private const val COMPLETION_CHANNEL_ID = "agent_completion"
        private const val NOTIFICATION_ID = 8001
        private const val COMPLETION_NOTIFICATION_BASE_ID = 9000
        internal const val ACTION_CANCEL_ALL = "dev.chungjungsoo.gptmobile.action.CANCEL_AGENT_RUNS"
        private const val WAKELOCK_TAG = "dev.chungjungsoo.gptmobile:agent_execution_wakelock"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour max safeguard
        private const val NOTIFICATION_THROTTLE_MS = 500L // Throttle notification updates

        fun start(context: Context) {
            AppLogRecorder.record("AgentService", "START_REQUESTED · elapsedMs=${SystemClock.elapsedRealtime()}")
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentRunForegroundService::class.java)
            )
        }
    }
}

private data class ActiveRunSummary(
    val runId: String,
    val profileUid: String,
    val phase: LocalInferencePhase?,
    val gatewayStage: String?,
    val gatewayMessage: String?,
    val gatewayCheckpoint: Int?
)

internal fun resolveNotificationContentText(context: Context, activeRuns: List<ActiveAgentRun>): String {
    val count = activeRuns.size.coerceAtLeast(1)
    if (activeRuns.size == 1) {
        val singleRun = activeRuns.first()
        singleRun.gatewayMessage?.takeIf { it.isNotBlank() }?.let { return it.take(120) }
        singleRun.gatewayStage?.takeIf { it.isNotBlank() }?.let {
            return it.replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
        when (singleRun.phase) {
            LocalInferencePhase.PREFILL -> return context.getString(R.string.agent_run_phase_prefill)
            LocalInferencePhase.GENERATING -> return context.getString(R.string.agent_run_phase_generating)
            null -> Unit
        }
    }
    return context.resources.getQuantityString(R.plurals.agent_runs_active, count, count)
}

internal fun resolveGatewayProgressPercent(run: ActiveAgentRun): Int? {
    val stage = run.gatewayStage?.lowercase()?.replace('-', '_') ?: return null
    return when {
        stage.contains("complete") -> 100
        stage.contains("final") || stage.contains("synth") -> 90
        stage.contains("verify") || stage.contains("test") || stage.contains("review") -> 75
        stage.contains("tool") || stage.contains("research") || stage.contains("prefetch") -> 55
        stage.contains("model") || stage.contains("reason") || stage.contains("generat") -> 35
        stage.contains("memory") || stage.contains("context") || stage.contains("plan") || stage.contains("preflight") -> 20
        stage.contains("start") || stage.contains("resum") -> 5
        else -> null
    }
}

internal fun shouldNotifyAgentRunsCompleted(
    wasActive: Boolean,
    isActive: Boolean,
    isAppBackground: Boolean
): Boolean = wasActive && !isActive && isAppBackground

internal fun shouldInterruptAgentRunsOnDestroy(stoppedBecauseInactive: Boolean, hasActiveRuns: Boolean): Boolean = !stoppedBecauseInactive && hasActiveRuns
