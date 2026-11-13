package dev.chungjungsoo.gptmobile.data.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.chungjungsoo.gptmobile.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class VoiceInstallState(val phase: String = "IDLE", val failure: String? = null) {
    val pending: Boolean get() = phase in setOf("QUEUED", "RUNNING", "WAITING_NETWORK")
}

internal object VoicePluginInstall {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(VoiceInstallState())
    val state = mutableState.asStateFlow()

    fun observe(phase: String, failure: String? = null, stopReason: Int? = null, progress: Int? = null) {
        mutableState.value = VoiceInstallState(phase, failure)
        // Only fixed enums and counters enter an explicitly selected voice report.
        runCatching {
            val fields = buildMap<String, Any?> {
                put("diagnostic_area", "VOICE")
                put("diagnostic_capture_mode", "SELECTED_CAPTURE")
                put("voice_install_state", phase)
                put("voice_install_scheduler", if (Build.VERSION.SDK_INT >= 34) "JOB_SCHEDULER" else "WORK_MANAGER")
                failure?.let { put("voice_install_failure", it) }
                stopReason?.let { put("voice_install_stop_reason", it) }
                progress?.let { put("voice_install_progress_percent", it.coerceIn(0, 100)) }
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics.fromFields("MANUAL_REPORT", fields, System.currentTimeMillis())
                ?.let(dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots::retain)
        }
    }

    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT >= 34 &&
            !state.value.pending &&
            context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null
        ) {
            observe("QUEUED")
        }
    }

    const val WORK = "voice-plugin-bundle-v1"
    const val JOB_ID = 40071
    private const val CHANNEL = "voice-plugin-install"

    fun start(context: Context) {
        observe("QUEUED")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startTransfer(context)
                return
            }
            val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VoicePluginInstallWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
            operation.result.addListener({
                runCatching { operation.result.get() }.onFailure { observe("FAILED", "SCHEDULE_EXCEPTION") }
            }, androidx.core.content.ContextCompat.getMainExecutor(context))
        } catch (_: Exception) {
            observe("FAILED", "SCHEDULE_EXCEPTION")
        }
    }

    @RequiresApi(34)
    private fun startTransfer(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, VoicePluginTransferService::class.java))
            .setRequiredNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
            .setEstimatedNetworkBytes(LocalVoiceModelStore.TOTAL_BYTES + KokoroModelPack.DOWNLOAD_BYTES, 0)
            .setUserInitiated(true)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) observe("FAILED", "SCHEDULE_REJECTED")
    }

    fun cancel(context: Context) {
        observe("CANCELLED")
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun notification(context: Context, downloaded: Long = 0, total: Long = 0, cancel: PendingIntent): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice plugin download", NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ai_notification)
            .setContentTitle("Voice Chat plugin")
            .setContentText(if (percent == 100) "Checking files" else "Downloading")
            .setProgress(100, percent, total == 0L || percent == 100)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    suspend fun install(context: Context, progress: (Long, Long) -> Unit): Boolean = coroutineScope {
        observe("RUNNING")
        val directory = File(context.noBackupFilesDir, "voice-models")
        val moonshine = LocalVoiceModelStore(directory)
        val kokoro = KokoroModelPack.store(directory)
        val observer = launch {
            combine(moonshine.state, kokoro.state) { recognition, playback ->
                (recognition.downloadedBytes + playback.downloadedBytes) to (recognition.totalBytes + playback.totalBytes)
            }.collect { (bytes, total) ->
                observe("RUNNING", progress = (bytes * 100 / total.coerceAtLeast(1)).toInt())
                progress(bytes, total)
            }
        }
        try {
            moonshine.refresh()
            kokoro.refresh()
            moonshine.install()
            if (moonshine.isInstalled()) kokoro.install()
            val ready = moonshine.isInstalled() && kokoro.isInstalled()
            observer.cancel()
            observe(if (ready) "COMPLETED" else "FAILED", if (ready) null else "ASSET_FAILURE")
            ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observer.cancel()
            observe("FAILED", "WORKER_EXCEPTION")
            throw failure
        } finally {
            observer.cancel()
        }
    }
}

class VoicePluginInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val manager = WorkManager.getInstance(context)
        val cancel = manager.createCancelPendingIntent(id)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notificationId = id.hashCode()
        return try {
            setForeground(ForegroundInfo(notificationId, VoicePluginInstall.notification(context, cancel = cancel), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val installed = VoicePluginInstall.install(context) { bytes, total ->
                notifications.notify(notificationId, VoicePluginInstall.notification(context, bytes, total, cancel))
            }
            if (installed) Result.success() else Result.failure()
        } catch (cancelled: CancellationException) {
            if (VoicePluginInstall.state.value.phase != "CANCELLED") VoicePluginInstall.observe("WAITING_NETWORK")
            throw cancelled
        } catch (_: Exception) {
            VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION")
            Result.failure()
        }
    }
}

@RequiresApi(34)
class VoicePluginTransferService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transfer: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val cancel = PendingIntent.getBroadcast(this, VoicePluginInstall.JOB_ID, Intent(this, VoicePluginCancelReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = VoicePluginInstall.notification(this, cancel = cancel)
        try {
            setNotification(params, VoicePluginInstall.JOB_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (_: Exception) {
            VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION")
            return false
        }
        val notifications = getSystemService(NotificationManager::class.java)
        transfer = scope.launch {
            try {
                VoicePluginInstall.install(this@VoicePluginTransferService) { bytes, total ->
                    notifications.notify(VoicePluginInstall.JOB_ID, VoicePluginInstall.notification(this@VoicePluginTransferService, bytes, total, cancel))
                }
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION")
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        transfer?.cancel()
        transfer = null
        val retry = params.stopReason in setOf(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, JobParameters.STOP_REASON_DEVICE_STATE)
        VoicePluginInstall.observe(if (retry) "WAITING_NETWORK" else "CANCELLED", stopReason = params.stopReason)
        return retry
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class VoicePluginCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = VoicePluginInstall.cancel(context)
}
