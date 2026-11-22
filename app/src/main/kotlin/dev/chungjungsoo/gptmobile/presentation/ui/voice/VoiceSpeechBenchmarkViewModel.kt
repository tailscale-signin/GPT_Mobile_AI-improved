package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.QualcommVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceBackendCapabilityStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBackendState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkIssue
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkMode
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkRunState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkRunner
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkStore
import dev.melo.kokoro.TimedKokoro
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class VoiceSpeechBenchmarkUiState(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val report: VoiceSpeechBenchmarkReport? = null,
    val history: List<VoiceSpeechBenchmarkReport> = emptyList(),
    val saved: Boolean = true,
    val notice: String? = null
)

/** One observable owner token prevents a late completion from releasing another worker's audio. */
internal class VoiceSpeechBenchmarkOwnership {
    private val mutable = MutableStateFlow<Lease?>(null)
    val state = mutable.asStateFlow()

    fun acquire(): Lease? {
        val lease = Lease()
        return lease.takeIf { mutable.compareAndSet(null, it) }
    }

    inner class Lease internal constructor() {
        fun release(): Boolean = mutable.compareAndSet(this, null)
    }
}

@HiltViewModel
internal class VoiceSpeechBenchmarkViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val mutable = MutableStateFlow(VoiceSpeechBenchmarkUiState())
    val state = mutable.asStateFlow()
    private val runner = AtomicReference<VoiceSpeechBenchmarkRunner?>(null)
    private val cancelled = AtomicBoolean(false)
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { runCatching { VoiceSpeechBenchmarkStore(context).read() }.getOrDefault(emptyList()) }
            mutable.update { it.copy(loaded = true, history = history, report = history.firstOrNull()) }
        }
    }

    fun selectHistory(index: Int) {
        if (!mutable.value.running) mutable.value.history.getOrNull(index)?.let { report -> mutable.update { it.copy(report = report) } }
    }

    fun start(mode: VoiceSpeechBenchmarkMode, backends: List<VoiceSpeechBackend>, voice: KokoroVoice, threads: Int, rate: Float) {
        if (!mutable.value.loaded || mutable.value.running || backends.isEmpty()) return
        val lease = benchmarkLease.acquire()
        if (lease == null) {
            mutable.update { it.copy(notice = "Another speech benchmark is finishing. Try again shortly.") }
            return
        }
        cancelled.set(false)
        mutable.update { it.copy(running = true, cancelling = false, saved = true, notice = null, report = null) }
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { execute(mode, backends, voice, threads, rate) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "The speech benchmark could not start. Try again.") }
            } catch (_: LinkageError) {
                mutable.update { it.copy(notice = "The speech runtime could not be loaded. Repair the installed voice model and try again.") }
            } finally {
                runner.set(null)
                mutable.update { it.copy(running = false, cancelling = false) }
            }
        }
        job?.invokeOnCompletion { lease.release() }
    }

    private fun execute(mode: VoiceSpeechBenchmarkMode, backends: List<VoiceSpeechBackend>, voice: KokoroVoice, threads: Int, rate: Float) {
        val store = VoiceSpeechBenchmarkStore(context)
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        var report = VoiceSpeechBenchmarkReport(
            System.currentTimeMillis(),
            VoiceSpeechBenchmarkConfiguration(
                mode, VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS.filter { it in backends }, voice,
                threads.coerceIn(1, 4), rate.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
                KokoroModelPack.MODEL_SHA256, TimedKokoro.RUNTIME_ID, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
                VoiceBackendCapabilityStore(context).deviceKey
            )
        )
        var persistedSamples = -1
        fun publish(value: VoiceSpeechBenchmarkReport) {
            val normalized = if (value.isRunning) {
                value
            } else {
                value.copy(
                    results = value.results.map { result ->
                        if (result.state in setOf(VoiceSpeechBenchmarkBackendState.PENDING, VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING)) {
                            result.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN)
                        } else {
                            result
                        }
                    }
                )
            }
            report = normalized
            // Stage notifications stay in memory; disk I/O cannot inflate the first-PCM measurement.
            val persist = normalized.completedSamples != persistedSamples || !normalized.isRunning
            val saved = if (persist) store.save(normalized) else mutable.value.saved
            if (persist) persistedSamples = normalized.completedSamples
            mutable.update { current ->
                current.copy(report = normalized, saved = saved, history = (listOf(normalized) + current.history.filterNot { it.startedAtMillis == normalized.startedAtMillis }).take(5))
            }
        }
        var leased = false
        val pending = AtomicReference<QualcommVoicePhraseEngineFactory?>(null)
        try {
            if (cancelled.get()) throw CancellationException("Cancelled")
            leased = models.acquireUse()
            if (!leased) {
                publish(report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE, finishedAtMillis = System.currentTimeMillis()))
                return
            }
            val thermal = context.getSystemService(PowerManager::class.java)
            val measurement = VoiceSpeechBenchmarkRunner(
                create = { backend ->
                    val factory = QualcommVoicePhraseEngineFactory(context, backend, {})
                    pending.set(factory)
                    try {
                        factory.createBenchmarkEngine(models.modelDirectory(), voice.lexicon, report.configuration.threads)
                    } finally {
                        pending.compareAndSet(factory, null)
                    }
                },
                cancelPreparation = { pending.get()?.cancelPending() },
                thermalStatus = { runCatching { thermal?.currentThermalStatus }.getOrNull() }
            )
            runner.set(measurement)
            if (cancelled.get()) measurement.cancel()
            measurement.run(report, ::publish)
        } catch (_: CancellationException) {
            publish(report.copy(state = VoiceSpeechBenchmarkRunState.CANCELLED, issue = VoiceSpeechBenchmarkIssue.CANCELLED, finishedAtMillis = System.currentTimeMillis()))
        } catch (_: Exception) {
            publish(report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.EXECUTION_FAILED, finishedAtMillis = System.currentTimeMillis()))
        } catch (_: LinkageError) {
            publish(report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.EXECUTION_FAILED, finishedAtMillis = System.currentTimeMillis()))
        } finally {
            if (report.isRunning) {
                publish(report.copy(state = VoiceSpeechBenchmarkRunState.INTERRUPTED, finishedAtMillis = System.currentTimeMillis()))
            }
            if (leased) models.releaseUse()
        }
    }

    fun cancel() {
        if (!mutable.value.running) return
        cancelled.set(true)
        mutable.update { it.copy(cancelling = true) }
        runner.get()?.cancel()
    }

    override fun onCleared() {
        cancelled.set(true)
        runner.get()?.cancel()
        super.onCleared()
    }

    companion object {
        private val benchmarkLease = VoiceSpeechBenchmarkOwnership()
        val audioOwnership = benchmarkLease.state
    }
}
