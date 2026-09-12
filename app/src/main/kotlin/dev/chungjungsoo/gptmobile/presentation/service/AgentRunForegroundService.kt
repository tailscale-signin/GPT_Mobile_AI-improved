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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.MainActivity
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.LocalInferencePhase
import dev.chungjungsoo.gptmobile.presentation.util.AppForegroundTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AgentRunForegroundService : Service() {

    @Inject
    lateinit var agentRunCoordinator: AgentRunCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeRunsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wasActive = false
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        observeActiveRuns()
    }

    private fun observeActiveRuns() {
        activeRunsJob = serviceScope.launch {
            agentRunCoordinator.activeRuns
                .map { runs ->
                    val summaries = runs.map {
                        ActiveRunSummary(
                            runId = it.runId,
                            profileUid = it.profileUid,
                            phase = it.phase
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
                        updateNotification(runs)
                        wasActive = true
                    } else if (wasActive) {
                        ServiceCompat.stopForeground(this@AgentRunForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        if (shouldNotifyAgentRunsCompleted(wasActive, isActive, AppForegroundTracker.isBackgrounded)) {
                            showCompletionNotification()
                            triggerCompletionVibration()
                        }
                        stopSelf()
                    } else {
                        stopSelf()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            agentRunCoordinator.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
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
        val notification = buildNotification(activeRuns)
        if (!isForeground) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            isForeground = true
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(activeRuns: List<ActiveAgentRun>): Notification {
        val contentText = resolveNotificationContentText(this, activeRuns)

        val openApp = buildOpenAppPendingIntent(1)

        val cancelIntent = Intent(this, AgentRunForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelRuns = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gpt_mobile_monochrome_foreground)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.cancel_agent_runs), cancelRuns)
            .build()
    }

    private fun showCompletionNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildCompletionNotification())
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

    private fun buildCompletionNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_gpt_mobile_monochrome_foreground)
        .setContentTitle(getString(R.string.agent_completion_notification_title))
        .setContentText(getString(R.string.agent_completion_notification_text))
        .setContentIntent(buildOpenAppPendingIntent(2))
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()

    private fun buildOpenAppPendingIntent(requestCode: Int): PendingIntent {
        val openAppIntent = Intent().setClass(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "agent_runs"
        private const val NOTIFICATION_ID = 8001
        private const val ACTION_CANCEL_ALL = "dev.chungjungsoo.gptmobile.action.CANCEL_AGENT_RUNS"
        private const val WAKELOCK_TAG = "dev.chungjungsoo.gptmobile:agent_execution_wakelock"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour max safeguard

        fun start(context: Context) {
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
    val phase: LocalInferencePhase?
)

internal fun resolveNotificationContentText(context: Context, activeRuns: List<ActiveAgentRun>): String {
    val count = activeRuns.size.coerceAtLeast(1)
    if (activeRuns.size == 1) {
        val singleRun = activeRuns.first()
        when (singleRun.phase) {
            LocalInferencePhase.PREFILL -> return context.getString(R.string.agent_run_phase_prefill)
            LocalInferencePhase.GENERATING -> return context.getString(R.string.agent_run_phase_generating)
            null -> Unit
        }
    }
    return context.resources.getQuantityString(R.plurals.agent_runs_active, count, count)
}

internal fun shouldNotifyAgentRunsCompleted(
    wasActive: Boolean,
    isActive: Boolean,
    isAppBackground: Boolean
): Boolean = wasActive && !isActive && isAppBackground

internal fun shouldInterruptAgentRunsOnDestroy(stoppedBecauseInactive: Boolean, hasActiveRuns: Boolean): Boolean = !stoppedBecauseInactive && hasActiveRuns
