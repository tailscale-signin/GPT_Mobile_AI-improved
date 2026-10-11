package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.presentation.service.BenchmarkForegroundService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns a benchmark independently of the screen that started it. */
@Singleton
class BenchmarkExecutionCoordinator @Inject constructor(
    @param:ApplicationContext context: Context,
    private val benchmarkStore: BenchmarkStore
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    init {
        if (preferences.getBoolean(KEY_RUNNING, false)) {
            preferences.edit().putBoolean(KEY_RUNNING, false).apply()
            preferences.edit().putString("status", "INTERRUPTED").apply()
            errorMutable.value = "The previous benchmark was interrupted when Android stopped the app. Its completed runs remain saved."
            recovery = scope.launch { benchmarkStore.markInterruptedRuns() }
        }
    }

    val isRunning: Boolean get() = active?.isCompleted == false

    @Synchronized
    fun launch(block: suspend () -> Unit): Job? {
        if (isRunning) return null
        stopReason = null
        val id = java.util.UUID.randomUUID().toString()
        sessionMutable.value = id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            recovery?.join()
            preferences.edit().putBoolean(KEY_RUNNING, true).putString("session_id", id).putString("status", "RUNNING").commit()
            block()
        }
        // Reserve ownership before Android can deliver a service cancellation.
        active = job
        job.invokeOnCompletion { failure ->
            synchronized(this) {
                if (active === job) {
                    progressMutable.value = null
                    sessionMutable.value = null
                    val outcome = when {
                        stopReason != null -> "STOPPED"
                        failure is kotlinx.coroutines.CancellationException -> "INTERRUPTED"
                        failure != null -> "FAILED"
                        else -> "FINISHED"
                    }
                    preferences.edit().putBoolean(KEY_RUNNING, false).putString("status", outcome).apply()
                    appContext.stopService(Intent(appContext, BenchmarkForegroundService::class.java))
                    active = null
                }
            }
        }
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, BenchmarkForegroundService::class.java))
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

    fun serviceUnavailable(reason: String) {
        errorMutable.value = reason
        cancel(reason)
    }

    fun cancel(reason: String = "Stopped by user.") {
        stopReason = reason
        active?.cancel()
    }

    companion object {
        private const val KEY_RUNNING = "running"
    }
}
