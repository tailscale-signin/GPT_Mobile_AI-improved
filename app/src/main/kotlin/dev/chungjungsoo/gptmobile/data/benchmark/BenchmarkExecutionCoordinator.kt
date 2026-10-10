package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.presentation.service.BenchmarkForegroundService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Volatile
    private var active: Job? = null

    @Volatile
    var stopReason: String? = null
        private set

    init {
        if (preferences.getBoolean(KEY_RUNNING, false)) {
            preferences.edit().putBoolean(KEY_RUNNING, false).apply()
            errorMutable.value = "The previous benchmark was interrupted when Android stopped the app. Its completed runs remain saved."
            scope.launch { benchmarkStore.markInterruptedRuns() }
        }
    }

    val isRunning: Boolean get() = active?.isActive == true

    @Synchronized
    fun launch(block: suspend () -> Unit): Job? {
        if (isRunning) return null
        stopReason = null
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, BenchmarkForegroundService::class.java)
            )
        } catch (error: Exception) {
            errorMutable.value = "Android could not start background benchmark tracking: ${dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(error.message ?: "service unavailable").take(300)}"
            return null
        }
        errorMutable.value = null
        val job = scope.launch {
            preferences.edit().putBoolean(KEY_RUNNING, true).apply()
            try {
                block()
            } finally {
                progressMutable.value = null
                preferences.edit().putBoolean(KEY_RUNNING, false).apply()
                appContext.stopService(Intent(appContext, BenchmarkForegroundService::class.java))
            }
        }
        active = job
        return job
    }

    fun cancel(reason: String = "Stopped by user.") {
        stopReason = reason
        active?.cancel()
    }

    companion object {
        private const val KEY_RUNNING = "running"
    }
}
