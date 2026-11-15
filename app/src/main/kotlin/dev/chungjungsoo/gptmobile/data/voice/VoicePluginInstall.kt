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
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.chungjungsoo.gptmobile.R
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
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

internal object VoicePluginInstall {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(VoiceInstallState())
    val state = mutableState.asStateFlow()
    private val attempt = VoiceInstallAttempt()

    @Volatile private var scheduling = false

    @Volatile private var scheduleGeneration = 0L

    private var startedAtElapsedMillis: Long? = null
    private var activeWorkId: UUID? = null

    fun observe(
        phase: String,
        failure: String? = null,
        stopReason: Int? = null,
        progress: Int? = state.value.progress,
        pendingReason: String? = if (phase == "RUNNING") "EXECUTING" else null,
        transfer: VoiceInstallTransfer? = state.value.transfer,
        owner: String? = null
    ) {
        attempt.runIfCurrent(owner) {
            val elapsed = startedAtElapsedMillis?.let { start -> (SystemClock.elapsedRealtime() - start).takeIf { it >= 0 } }
            val observed = VoiceInstallState(phase, failure, pendingReason, stopReason, progress, elapsed, transfer)
            mutableState.value = observed
            // Only fixed enums and counters enter an explicitly selected voice report.
            runCatching {
                val fields = observed.diagnosticFields(if (Build.VERSION.SDK_INT >= 34) "JOB_SCHEDULER" else "WORK_MANAGER")
                dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics.fromFields("MANUAL_REPORT", fields, System.currentTimeMillis())
                    ?.let(dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots::retain)
            }
        }
    }

    fun refresh(context: Context) {
        // A pending label is not proof that Android still owns a job. Reconcile it
        // while the entry screen is visible, but never race an unfinished enqueue.
        if (scheduling) return
        if (Build.VERSION.SDK_INT >= 34) {
            val scheduled = runCatching { context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) }
            if (scheduled.isFailure) {
                if (state.value.pending) observe(state.value.phase, state.value.failure, pendingReason = "UNAVAILABLE")
                return
            }
            val job = scheduled.getOrNull()
            if (job != null) {
                if (state.value.phase in setOf("COMPLETED", "FAILED", "CANCELLED")) return
                val owner = job.extras.getString(ATTEMPT) ?: "legacy-job-$JOB_ID"
                if (!attempt.restore(owner)) return
                val reason = runCatching { context.getSystemService(JobScheduler::class.java).getPendingJobReason(JOB_ID) }
                    .fold(::pendingReason, { "UNAVAILABLE" })
                val phase = when {
                    reason == "EXECUTING" || attempt.hasActiveTransfer(owner) -> "RUNNING"
                    reason == "CONNECTIVITY" -> "WAITING_NETWORK"
                    else -> "QUEUED"
                }
                observeScheduled(phase, reason, owner)
            } else if (state.value.pending && !attempt.hasActiveTransfer()) {
                observe("FAILED", "WORK_NOT_FOUND", pendingReason = "MISSING")
            }
            return
        }
        val generation = scheduleGeneration
        val pendingWork = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK)
        pendingWork.addListener({
            if (generation != scheduleGeneration || scheduling) return@addListener
            val result = runCatching { pendingWork.get() }
            if (result.isFailure) {
                if (state.value.pending) observe(state.value.phase, state.value.failure, pendingReason = "UNAVAILABLE")
                return@addListener
            }
            val work = result.getOrThrow()
            val active = work.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: work.firstOrNull { it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }
            if (active != null) {
                activeWorkId = active.id
                val owner = workOwner(active)
                if (!attempt.restore(owner)) return@addListener
                observeScheduled(requireNotNull(phaseForWorkStates(listOf(active.state))), if (active.state == WorkInfo.State.BLOCKED) "OTHER_CONSTRAINT" else null, owner)
            } else if (state.value.pending && !attempt.hasActiveTransfer()) {
                when (work.firstOrNull { it.id == activeWorkId }?.state) {
                    WorkInfo.State.SUCCEEDED -> observe("COMPLETED")
                    WorkInfo.State.FAILED -> observe("FAILED")
                    WorkInfo.State.CANCELLED -> observe("CANCELLED")
                    else -> observe("FAILED", "WORK_NOT_FOUND", pendingReason = "MISSING")
                }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    fun captureReportObservation(recognition: LocalVoiceModelStatus, playback: LocalVoiceModelStatus) {
        val scheduler = if (Build.VERSION.SDK_INT >= 34) "JOB_SCHEDULER" else "WORK_MANAGER"
        val fields = state.value.diagnosticFields(scheduler) + mapOf(
            "voice_install_recognition_status" to recognition.name,
            "voice_install_playback_status" to playback.name
        )
        dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics.fromFields("MANUAL_REPORT", fields, System.currentTimeMillis())
            ?.let(dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots::retain)
    }

    private fun observeScheduled(phase: String, reason: String?, owner: String) {
        attempt.runIfCurrent(owner) {
            if (!scheduling && state.value.phase !in setOf("COMPLETED", "FAILED", "CANCELLED")) observe(phase, pendingReason = reason, owner = owner)
        }
    }

    @RequiresApi(34)
    internal fun pendingReason(reason: Int): String? = when (reason) {
        JobScheduler.PENDING_JOB_REASON_UNDEFINED -> "UNDEFINED"
        JobScheduler.PENDING_JOB_REASON_APP -> "APP_STATE"
        JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "APP_STANDBY"
        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "BACKGROUND_RESTRICTION"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "CONNECTIVITY"
        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "DEVICE_STATE"
        JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION -> "SCHEDULER_OPTIMIZATION"
        JobScheduler.PENDING_JOB_REASON_QUOTA -> "QUOTA"
        JobScheduler.PENDING_JOB_REASON_USER -> "USER"
        JobScheduler.PENDING_JOB_REASON_EXECUTING -> "EXECUTING"
        JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID -> "MISSING"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW,
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING,
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER,
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE,
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY,
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH,
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "OTHER_CONSTRAINT"
        else -> null
    }

    internal fun phaseForWorkStates(states: List<WorkInfo.State>): String? = when {
        WorkInfo.State.RUNNING in states -> "RUNNING"
        states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> "QUEUED"
        else -> null
    }

    const val WORK = "voice-plugin-bundle-v1"
    const val JOB_ID = 40071
    const val ATTEMPT = "voice_install_attempt"
    const val CANCEL_ACTION = "dev.chungjungsoo.gptmobile.voice.CANCEL_INSTALL"
    private const val ATTEMPT_TAG = "voice-install-attempt:"
    private const val CHANNEL = "voice-plugin-install"

    internal fun claimAttempt(owner: String): Boolean = attempt.restore(owner)
    internal fun ownsAttempt(owner: String): Boolean = attempt.owns(owner)
    private fun workOwner(work: WorkInfo): String = work.tags.firstOrNull { it.startsWith(ATTEMPT_TAG) }?.removePrefix(ATTEMPT_TAG) ?: "legacy-work-${work.id}"

    private fun beginAttempt(): String = UUID.randomUUID().toString().also { owner ->
        attempt.begin(owner)
        startedAtElapsedMillis = SystemClock.elapsedRealtime()
        observe("QUEUED", progress = null, transfer = null)
    }

    @Synchronized
    fun start(context: Context) {
        if (scheduling) return
        if (state.value.pending) {
            refresh(context)
            if (state.value.pending) return
        }
        val generation = ++scheduleGeneration
        scheduling = true
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                if (context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) == null) startTransfer(context, beginAttempt())
                scheduling = false
                refresh(context)
                return
            }
            // Discover an already-owned KEEP job before assigning a new attempt.
            // A cold-screen tap must not invalidate a worker that is already running.
            val manager = WorkManager.getInstance(context)
            val work = manager.getWorkInfosForUniqueWork(WORK)
            work.addListener({
                if (generation != scheduleGeneration) return@addListener
                try {
                    val active = work.get().firstOrNull { !it.state.isFinished }
                    if (active != null) {
                        // Cancellation is asynchronous: KEEP may still expose the
                        // cancelled owner here. Never revive it or enqueue a duplicate.
                        val restored = attempt.restore(workOwner(active))
                        scheduling = false
                        if (restored) {
                            activeWorkId = active.id
                            refresh(context)
                        }
                    } else {
                        val owner = beginAttempt()
                        val request = OneTimeWorkRequestBuilder<VoicePluginInstallWorker>()
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setInputData(workDataOf(ATTEMPT to owner))
                            .addTag(ATTEMPT_TAG + owner)
                            .build()
                        activeWorkId = request.id
                        val operation = manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
                        operation.result.addListener({
                            if (generation == scheduleGeneration) {
                                scheduling = false
                                runCatching { operation.result.get() }
                                    .onSuccess { refresh(context) }
                                    .onFailure { observe("FAILED", "SCHEDULE_EXCEPTION", owner = owner) }
                            }
                        }, androidx.core.content.ContextCompat.getMainExecutor(context))
                    }
                } catch (_: Exception) {
                    scheduling = false
                    observe("FAILED", "SCHEDULE_EXCEPTION")
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(context))
        } catch (_: Exception) {
            scheduling = false
            observe("FAILED", "SCHEDULE_EXCEPTION")
        }
    }

    @RequiresApi(34)
    private fun startTransfer(context: Context, owner: String) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, VoicePluginTransferService::class.java))
            .setRequiredNetwork(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    // Builder defaults exclude VPNs. A user-selected VPN is a valid
                    // internet route; do not wait forever for a non-VPN alternative.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            )
            .setEstimatedNetworkBytes(LocalVoiceModelStore.TOTAL_BYTES + KokoroModelPack.DOWNLOAD_BYTES, 0)
            .setUserInitiated(true)
            .setExtras(PersistableBundle().apply { putString(ATTEMPT, owner) })
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) observe("FAILED", "SCHEDULE_REJECTED")
    }

    @Synchronized
    fun cancel(context: Context, owner: String? = null) {
        attempt.runIfCurrent(owner) {
            scheduleGeneration++
            attempt.cancel()
            scheduling = false
            activeWorkId = null
            observe("CANCELLED")
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }

    internal fun notificationCancelAction(context: Context, owner: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        JOB_ID,
        notificationCancelRequest(context, owner),
        PendingIntent.FLAG_IMMUTABLE
    )

    internal fun notificationCancelRequest(context: Context, owner: String): Intent = Intent(context, VoicePluginCancelReceiver::class.java)
        .setAction(CANCEL_ACTION)
        .setData(Uri.Builder().scheme("gptmobile-voice-install").authority("cancel").appendPath(owner).build())
        .putExtra(ATTEMPT, owner)

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

    suspend fun install(
        context: Context,
        owner: String,
        sourceFactory: () -> LocalVoiceAssetSource = { HttpsVoiceAssetSource() },
        progress: (Long, Long) -> Unit
    ): Boolean = coroutineScope {
        if (!attempt.owns(owner)) return@coroutineScope false
        observe("RUNNING", owner = owner)
        val directory = File(context.noBackupFilesDir, "voice-models")
        val moonshine = LocalVoiceModelStore(directory, sourceFactory)
        val kokoro = KokoroModelPack.store(directory, sourceFactory)
        if (!attempt.acquireTransfer(owner)) return@coroutineScope false
        val observer = launch {
            combine(moonshine.state, kokoro.state) { recognition, playback ->
                Triple(recognition.downloadedBytes + playback.downloadedBytes, recognition.totalBytes + playback.totalBytes, transferObservation(recognition, playback))
            }.collect { (bytes, total, transfer) ->
                attempt.runIfCurrent(owner) {
                    observe("RUNNING", progress = (bytes * 100 / total.coerceAtLeast(1)).toInt(), transfer = transfer, owner = owner)
                    progress(bytes, total)
                }
            }
        }
        try {
            moonshine.refresh()
            kokoro.refresh()
            moonshine.install()
            if (moonshine.isInstalled()) kokoro.install()
            val ready = moonshine.isInstalled() && kokoro.isInstalled()
            observer.cancel()
            observe(if (ready) "COMPLETED" else "FAILED", if (ready) null else "ASSET_FAILURE", transfer = transferObservation(moonshine.state.value, kokoro.state.value), owner = owner)
            ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observer.cancel()
            observe("FAILED", "WORKER_EXCEPTION", owner = owner)
            throw failure
        } finally {
            observer.cancel()
            attempt.releaseTransfer(owner)
        }
    }

    internal fun transferObservation(recognition: LocalVoiceModelState, playback: LocalVoiceModelState): VoiceInstallTransfer? {
        val models = listOf("MOONSHINE" to recognition, "KOKORO" to playback)
        val selected = models.firstOrNull { it.second.status == LocalVoiceModelStatus.FAILED }
            ?: models.firstOrNull { it.second.status == LocalVoiceModelStatus.DOWNLOADING }
            ?: models.firstOrNull { it.second.status == LocalVoiceModelStatus.CHECKING }
            ?: models.lastOrNull()?.takeIf { models.all { model -> model.second.status == LocalVoiceModelStatus.READY } }
            ?: return null
        return VoiceInstallTransfer(
            selected.first,
            selected.second.transferStage,
            recognition.downloadedBytes + playback.downloadedBytes,
            recognition.totalBytes + playback.totalBytes,
            selected.second.failureKind,
            selected.second.httpStatus
        )
    }
}

class VoicePluginInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(VoicePluginInstall.ATTEMPT) ?: "legacy-work-$id"
        if (!VoicePluginInstall.claimAttempt(owner)) return Result.failure()
        val context = applicationContext
        val manager = WorkManager.getInstance(context)
        val cancel = manager.createCancelPendingIntent(id)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notificationId = id.hashCode()
        return try {
            setForeground(ForegroundInfo(notificationId, VoicePluginInstall.notification(context, cancel = cancel), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val installed = VoicePluginInstall.install(context, owner) { bytes, total ->
                notifications.notify(notificationId, VoicePluginInstall.notification(context, bytes, total, cancel))
            }
            if (installed) Result.success() else Result.failure()
        } catch (cancelled: CancellationException) {
            if (VoicePluginInstall.state.value.phase != "CANCELLED") {
                val reason = stopReason.takeIf { it >= 0 }
                VoicePluginInstall.observe(
                    if (reason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY) "WAITING_NETWORK" else "QUEUED",
                    stopReason = reason,
                    pendingReason = when (reason) {
                        JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "CONNECTIVITY"
                        JobParameters.STOP_REASON_DEVICE_STATE -> "DEVICE_STATE"
                        else -> "UNAVAILABLE"
                    },
                    owner = owner
                )
            }
            throw cancelled
        } catch (_: Exception) {
            VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION", owner = owner)
            Result.failure()
        }
    }
}

@RequiresApi(34)
class VoicePluginTransferService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transfer: Job? = null
    private var transferOwner: String? = null
    private var transferNetwork: AtomicReference<Network>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val owner = params.extras.getString(VoicePluginInstall.ATTEMPT) ?: "legacy-job-${VoicePluginInstall.JOB_ID}"
        if (!VoicePluginInstall.claimAttempt(owner)) return false
        val network = params.network ?: run {
            VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION", pendingReason = "CONNECTIVITY", owner = owner)
            return false
        }
        val cancel = VoicePluginInstall.notificationCancelAction(this, owner)
        val notification = VoicePluginInstall.notification(this, cancel = cancel)
        try {
            setNotification(params, VoicePluginInstall.JOB_ID, notification, JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (_: Exception) {
            VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION", owner = owner)
            return false
        }
        val notifications = getSystemService(NotificationManager::class.java)
        transfer?.cancel()
        transferOwner = owner
        val assignedNetwork = AtomicReference(network)
        transferNetwork = assignedNetwork
        transfer = scope.launch {
            try {
                val sourceFactory = {
                    HttpsVoiceAssetSource { url -> assignedNetwork.get().openConnection(url) }
                }
                VoicePluginInstall.install(this@VoicePluginTransferService, owner, sourceFactory) { bytes, total ->
                    notifications.notify(VoicePluginInstall.JOB_ID, VoicePluginInstall.notification(this@VoicePluginTransferService, bytes, total, cancel))
                }
                if (VoicePluginInstall.ownsAttempt(owner)) jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VoicePluginInstall.observe("FAILED", "WORKER_EXCEPTION", owner = owner)
                if (VoicePluginInstall.ownsAttempt(owner)) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onNetworkChanged(params: JobParameters) {
        val owner = params.extras.getString(VoicePluginInstall.ATTEMPT) ?: "legacy-job-${VoicePluginInstall.JOB_ID}"
        if (transferOwner == owner) params.network?.let { transferNetwork?.set(it) }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val owner = params.extras.getString(VoicePluginInstall.ATTEMPT) ?: "legacy-job-${VoicePluginInstall.JOB_ID}"
        if (transferOwner == owner) {
            transfer?.cancel()
            transfer = null
            transferOwner = null
            transferNetwork = null
        }
        val retry = params.stopReason in setOf(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY, JobParameters.STOP_REASON_DEVICE_STATE)
        VoicePluginInstall.observe(
            if (params.stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY) {
                "WAITING_NETWORK"
            } else if (retry) {
                "QUEUED"
            } else {
                "CANCELLED"
            },
            stopReason = params.stopReason,
            pendingReason = when (params.stopReason) {
                JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "CONNECTIVITY"
                JobParameters.STOP_REASON_DEVICE_STATE -> "DEVICE_STATE"
                else -> null
            },
            owner = owner
        )
        return retry && VoicePluginInstall.ownsAttempt(owner)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class VoicePluginCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val owner = intent.getStringExtra(VoicePluginInstall.ATTEMPT) ?: return
        if (intent.action != VoicePluginInstall.CANCEL_ACTION ||
            intent.data?.scheme != "gptmobile-voice-install" ||
            intent.data?.authority != "cancel" ||
            intent.data?.pathSegments != listOf(owner)
        ) {
            return
        }
        VoicePluginInstall.cancel(context, owner)
    }
}
