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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal object VoicePluginInstall {
    const val WORK = "voice-plugin-bundle-v1"
    const val JOB_ID = 40071
    private const val CHANNEL = "voice-plugin-install"

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            startTransfer(context)
            return
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<VoicePluginInstallWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
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
        if (runCatching { scheduler.schedule(job) }.getOrNull() != JobScheduler.RESULT_SUCCESS) {
            android.widget.Toast.makeText(context, "Could not schedule the voice download. Try again with the app open.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun notification(context: Context, downloaded: Long = 0, total: Long = 0, cancel: PendingIntent): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice plugin download", NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Voice Chat plugin")
            .setContentText(if (percent == 100) "Checking files" else "Downloading")
            .setProgress(100, percent, total == 0L || percent == 100)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    suspend fun install(context: Context, progress: (Long, Long) -> Unit): Boolean = coroutineScope {
        val directory = File(context.noBackupFilesDir, "voice-models")
        val moonshine = LocalVoiceModelStore(directory)
        val kokoro = KokoroModelPack.store(directory)
        val observer = launch {
            combine(moonshine.state, kokoro.state) { recognition, playback ->
                (recognition.downloadedBytes + playback.downloadedBytes) to (recognition.totalBytes + playback.totalBytes)
            }.collect { (bytes, total) -> progress(bytes, total) }
        }
        try {
            moonshine.refresh()
            kokoro.refresh()
            moonshine.install()
            if (moonshine.isInstalled()) kokoro.install()
            moonshine.isInstalled() && kokoro.isInstalled()
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
        setForeground(ForegroundInfo(notificationId, VoicePluginInstall.notification(context, cancel = cancel), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        val installed = VoicePluginInstall.install(context) { bytes, total ->
            notifications.notify(notificationId, VoicePluginInstall.notification(context, bytes, total, cancel))
        }
        return if (installed) Result.success() else Result.failure()
    }
}

@RequiresApi(34)
class VoicePluginTransferService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transfer: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val cancel = PendingIntent.getBroadcast(this, VoicePluginInstall.JOB_ID, Intent(this, VoicePluginCancelReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = VoicePluginInstall.notification(this, cancel = cancel)
        setNotification(params, VoicePluginInstall.JOB_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
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
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        transfer?.cancel()
        transfer = null
        return params.stopReason in setOf(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, JobParameters.STOP_REASON_DEVICE_STATE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class VoicePluginCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = VoicePluginInstall.cancel(context)
}
