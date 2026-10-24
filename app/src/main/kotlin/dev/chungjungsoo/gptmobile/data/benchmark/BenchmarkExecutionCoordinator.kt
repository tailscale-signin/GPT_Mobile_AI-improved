package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.presentation.service.BenchmarkForegroundService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Owns a benchmark independently of the screen that started it. */
@Singleton
class BenchmarkExecutionCoordinator @Inject constructor(
    @param:ApplicationContext context: Context,
    private val benchmarkStore: BenchmarkStore,
    private val batchRunner: BenchmarkBatchRunner? = null
) {
    private val appContext = context.applicationContext

    // Completion publishes the durable disposition. A root launch still needs a handler:
    // SupervisorJob alone otherwise forwards a storage exception to Android's crash handler.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Benchmark", dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(failure.message ?: "Benchmark worker failed."), "E")
        }
    )
    private val preferences = appContext.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE)

    internal val progressMutable = kotlinx.coroutines.flow.MutableStateFlow<BenchmarkProgress?>(null)
    internal val errorMutable = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val sessionMutable = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val sessionId = sessionMutable.asStateFlow()

    @Volatile
    private var active: Job? = null

    @Volatile
    var stopReason: String? = null
        private set

    private var recovery: Job? = null

    @Volatile private var recoveryFailure: Throwable? = null
    private var foregroundReady: CompletableDeferred<Unit>? = null

    init {
        if (preferences.getBoolean(KEY_RUNNING, false)) {
            preferences.edit().putBoolean(KEY_RUNNING, false).apply()
            preferences.edit().putString("status", "INTERRUPTED").apply()
            errorMutable.value = "The previous benchmark was interrupted when Android stopped the app. Its completed runs remain saved."
        }
        recovery = scope.launch {
            try {
                benchmarkStore.markInterruptedRuns()
                benchmarkStore.recoverBatches()
            } catch (failure: Exception) {
                if (failure !is kotlinx.coroutines.CancellationException) {
                    recoveryFailure = failure
                    publishFailure(failure)
                }
                throw failure
            }
        }
    }

    // Job completion can be observed before its completion handler finishes.
    // Keep ownership until status persistence and foreground-service cleanup finish.
    val isRunning: Boolean get() = active != null

    @Synchronized
    fun launch(session: String = java.util.UUID.randomUUID().toString(), prepare: suspend () -> Unit = {}, block: suspend () -> Unit): Job? {
        if (isRunning) return null
        stopReason = null
        val id = session
        sessionMutable.value = id
        val ready = CompletableDeferred<Unit>()
        foregroundReady = ready
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Starting the service is asynchronous. Fast failures/cancellation must
                // not stop it before Android receives the foreground notification.
                recovery?.join()
                recoveryFailure?.let { throw it }
                withTimeout(10_000) { prepare() }
                withTimeout(10_000) { ready.await() }
                preferences.edit().putBoolean(KEY_RUNNING, true).putString("session_id", id).putString("status", "RUNNING").commit()
                block()
            } catch (failure: BenchmarkStorageFailure) {
                currentCoroutineContext().ensureActive()
                pauseAfterStorageFailure(id, failure)
                throw failure
            }
        }
        // Reserve ownership before Android can deliver a service cancellation.
        active = job
        job.invokeOnCompletion { failure ->
            synchronized(this) {
                if (active === job) {
                    val durable = benchmarkStore.batches.value.firstOrNull { it.id == id }?.state
                    val outcome = when {
                        durable == BenchmarkBatchState.STORAGE_PAUSED -> durable.name
                        durable == BenchmarkBatchState.STOPPED || durable == BenchmarkBatchState.INTERRUPTED -> durable.name
                        stopReason != null -> "STOPPED"
                        failure is kotlinx.coroutines.CancellationException -> "INTERRUPTED"
                        failure is BenchmarkStorageFailure -> "STORAGE_PAUSED"
                        failure != null -> "FAILED"
                        else -> durable?.name ?: "FINISHED"
                    }
                    preferences.edit().putBoolean(KEY_RUNNING, false).putString("status", outcome).apply()
                    if (failure != null && failure !is kotlinx.coroutines.CancellationException) publishFailure(failure)
                    if (ready.isCompleted) appContext.stopService(Intent(appContext, BenchmarkForegroundService::class.java))
                    foregroundReady = null
                    active = null
                    progressMutable.value = null
                    // Publish idle last so observers can read the final outcome.
                    sessionMutable.value = null
                }
            }
        }
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, BenchmarkForegroundService::class.java).putExtra(BenchmarkForegroundService.EXTRA_SESSION_ID, id))
        } catch (error: Exception) {
            job.cancel()
            sessionMutable.value = null
            errorMutable.value = "Android could not start background benchmark tracking: ${dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(error.message ?: "service unavailable").take(300)}"
            return null
        }
        errorMutable.value = null
        job.start()
        return job
    }

    private fun publishFailure(failure: Throwable) {
        errorMutable.value = if (failure is BenchmarkStorageFailure) "Benchmark storage is unavailable. The queue is paused; saved results and pending profiles are retained." else dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(failure.message ?: "Benchmark execution failed.").take(500)
    }

    private suspend fun pauseAfterStorageFailure(id: String, failure: BenchmarkStorageFailure) = withContext(NonCancellable) {
        try {
            withTimeoutOrNull(5_000) {
                val batch = benchmarkStore.batch(id) ?: return@withTimeoutOrNull
                if (batch.state == BenchmarkBatchState.STORAGE_PAUSED) return@withTimeoutOrNull
                val now = System.currentTimeMillis()
                val reason = BenchmarkFailure("STORAGE_CHECKPOINT_FAILED", "checkpoint", "Canonical storage is unavailable; the queue is paused.", owner = "storage", scope = "batch")
                val activeItem = batch.items.firstOrNull { it.state == BenchmarkQueueState.RUNNING }
                val run = benchmarkStore.history.value.firstOrNull { it.id == activeItem?.runId }?.copy(finished = true, state = "INTERRUPTED", endedAt = now, stoppedReason = reason.message)
                benchmarkStore.checkpoint(batch.copy(state = BenchmarkBatchState.STORAGE_PAUSED, activeOrdinal = null, endedAt = now, storageWarning = reason.message, items = batch.items.map { if (it.state == BenchmarkQueueState.RUNNING) it.copy(state = BenchmarkQueueState.INTERRUPTED, endedAt = now, failure = reason) else it }), run, event = "STORAGE_PAUSED")
            }
        } catch (checkpointFailure: Exception) {
            // Preserve the original error; recovery will reconcile the last committed cursor.
            failure.addSuppressed(checkpointFailure)
        }
    }

    /** Called only after ServiceCompat.startForeground succeeds for this session. */
    @Synchronized
    fun serviceReady(id: String?): Boolean {
        if (id == null || id != sessionMutable.value || active?.isCancelled != false) return false
        return foregroundReady?.let {
            it.complete(Unit)
            true
        } ?: false
    }

    internal fun startBatch(batch: BenchmarkBatch): Job? = launch(batch.id, prepare = { benchmarkStore.checkpoint(batch, event = "MANIFEST_SAVED") }) {
        checkNotNull(batchRunner).run(batch.id) { progressMutable.value = it }
    }

    internal fun resumeBatch(id: String): Job? {
        val saved = benchmarkStore.batches.value.firstOrNull { it.id == id && it.canResume } ?: return null
        return launch(id) {
            benchmarkStore.checkpoint(saved.copy(state = BenchmarkBatchState.QUEUED, stopOrigin = null, endedAt = null, storageWarning = null), event = "RESUMED")
            checkNotNull(batchRunner).run(id) { progressMutable.value = it }
        }
    }

    fun skipProfile(id: String? = sessionMutable.value) {
        if (id == null || id != sessionMutable.value) return
        scope.launch {
            try {
                batchRunner?.requestStop(id, BenchmarkStopOrigin.USER_SKIP)
            } catch (failure: Exception) {
                errorMutable.value = "Could not save Skip. The queue has been paused."
                active?.cancel()
            }
        }
    }

    fun serviceUnavailable(reason: String, id: String? = sessionMutable.value) {
        if (id != sessionMutable.value) return
        errorMutable.value = reason
        cancel(reason, id, BenchmarkStopOrigin.SERVICE_INTERRUPTION)
    }

    fun cancel(reason: String = "Stopped by user.", id: String? = sessionMutable.value, origin: BenchmarkStopOrigin = BenchmarkStopOrigin.USER_STOP) {
        if (id == null || id != sessionMutable.value) return
        stopReason = reason
        val owner = active
        scope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    if (batchRunner?.requestStop(id, origin) != true) {
                        benchmarkStore.batch(id)?.let { batch ->
                            benchmarkStore.checkpoint(batch.copy(state = if (origin == BenchmarkStopOrigin.USER_STOP) BenchmarkBatchState.STOPPED else BenchmarkBatchState.INTERRUPTED, stopOrigin = origin, endedAt = System.currentTimeMillis()), event = origin.name)
                        }
                    }
                }
            } finally {
                if (sessionMutable.value == id && active === owner) owner?.cancel()
            }
        }
    }

    companion object {
        private const val KEY_RUNNING = "running"
    }
}
