package dev.chungjungsoo.gptmobile.presentation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionCoordinator
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkProgress
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps an explicitly started benchmark alive while the user leaves the screen or app. */
@AndroidEntryPoint
class BenchmarkForegroundService : Service() {
    @Inject
    lateinit var coordinatorProvider: Lazy<BenchmarkExecutionCoordinator>

    private val coordinator: BenchmarkExecutionCoordinator get() = coordinatorProvider.get()

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var published = false
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Benchmark progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // Do not resolve the benchmark/database graph before the startup deadline.
        publishForeground()
    }

    private fun publishForeground(): Boolean {
        // Every startForegroundService request needs promotion, including a
        // restart delivered while the previous session is shutting down.
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            published = true
            true
        } catch (_: Exception) {
            published = false
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!publishForeground()) {
            coordinator.serviceUnavailable("Android could not publish the benchmark foreground notification. The run was stopped; completed samples remain saved.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            coordinator.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!coordinator.serviceReady(intent?.getStringExtra(EXTRA_SESSION_ID))) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            serviceScope.launch {
                coordinator.progressMutable.collect { progress ->
                    if (published && progress != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(progress))
                }
            }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPTMobile:Benchmark")
                .apply { acquire(MAX_WAKE_LOCK_MS) }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(progress: BenchmarkProgress? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTIFICATION_EVENT_ID, java.util.UUID.randomUUID().toString()).putExtra(EXTRA_OPEN_BENCHMARKS, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BenchmarkForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Benchmark running")
            .setContentText(progress?.let { "${it.profileName} · ${it.testName} · ${it.completed.coerceAtMost(it.total)}/${it.total}" } ?: "Starting benchmark. Completed samples remain saved.")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setProgress(progress?.total ?: 0, progress?.completed ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.cancel("Android ended the benchmark foreground service time limit.")
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_OPEN_BENCHMARKS = "open_benchmarks"
        const val EXTRA_SESSION_ID = "benchmark_session_id"
        const val ACTION_STOP = "dev.chungjungsoo.gptmobile.action.STOP_BENCHMARK"
        const val CHANNEL_ID = "benchmark_execution"
        const val NOTIFICATION_ID = 8204
        const val MAX_WAKE_LOCK_MS = 2 * 60 * 60 * 1000L
    }
}
