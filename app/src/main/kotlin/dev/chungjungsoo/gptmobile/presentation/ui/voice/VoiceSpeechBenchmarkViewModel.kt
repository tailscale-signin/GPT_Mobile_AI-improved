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
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkWorkload
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechCalibrationPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackBenchmark
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackScenario
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackState
import dev.melo.kokoro.TimedKokoro
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val notice: String? = null,
    val nativeCleanupPending: Boolean = false
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
    private val playbackRunner = AtomicReference<VoiceSpeechPlaybackBenchmark?>(null)
    private val cancelled = AtomicBoolean(false)
    private val retiringPlayback = AtomicReference<VoiceSpeechPlaybackBenchmark?>(null)
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

    fun start(
        mode: VoiceSpeechBenchmarkMode,
        backends: List<VoiceSpeechBackend>,
        voice: KokoroVoice,
        threads: Int,
        rate: Float,
        workload: VoiceSpeechBenchmarkWorkload = VoiceSpeechBenchmarkWorkload.UTTERANCE,
        playback: Boolean = false,
        playbackScenarios: List<VoiceSpeechPlaybackScenario> = listOf(VoiceSpeechPlaybackScenario.COLD_START),
        calibrationPolicy: VoiceSpeechCalibrationPolicy = VoiceSpeechCalibrationPolicy.CONTROLLED_ZERO,
        captionOffsetMs: Int = 0
    ) {
        if (!mutable.value.loaded || mutable.value.running || backends.isEmpty()) return
        val lease = benchmarkLease.acquire()
        if (lease == null) {
            mutable.update { it.copy(notice = "Another speech benchmark is finishing. Try again shortly.") }
            return
        }
        cancelled.set(false)
        mutable.update { it.copy(running = true, cancelling = false, saved = true, notice = null, report = null, nativeCleanupPending = false) }
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { execute(mode, backends, voice, threads, rate, workload, playback, playbackScenarios, calibrationPolicy, captionOffsetMs) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "The speech benchmark could not start. Try again.") }
            } catch (_: LinkageError) {
                mutable.update { it.copy(notice = "The speech runtime could not be loaded. Repair the installed voice model and try again.") }
            } finally {
                runner.set(null)
                playbackRunner.set(null)
                mutable.update { it.copy(running = false, cancelling = false) }
            }
        }
        job?.invokeOnCompletion {
            val retiring = retiringPlayback.get()
            if (retiring == null || retiring.pendingRetirements.isEmpty()) {
                if (retiring != null) retiringPlayback.compareAndSet(retiring, null)
                mutable.update { it.copy(nativeCleanupPending = false) }
                lease.release()
            } else {
                retirementScope.launch {
                    retiring.awaitRetired()
                    retiringPlayback.compareAndSet(retiring, null)
                    mutable.update { it.copy(nativeCleanupPending = false, notice = "Native speech cleanup finished. You can start another test.") }
                    lease.release()
                }
            }
        }
    }

    private suspend fun execute(mode: VoiceSpeechBenchmarkMode, backends: List<VoiceSpeechBackend>, voice: KokoroVoice, threads: Int, rate: Float, workload: VoiceSpeechBenchmarkWorkload, playback: Boolean, playbackScenarios: List<VoiceSpeechPlaybackScenario>, calibrationPolicy: VoiceSpeechCalibrationPolicy, captionOffsetMs: Int) {
        val store = VoiceSpeechBenchmarkStore(context)
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        val started = System.currentTimeMillis()
        val previousOrder = mutable.value.history.firstOrNull()?.configuration?.backendOrderSeed
        val orderSeed = previousOrder?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: 0L
        var report = VoiceSpeechBenchmarkReport(
            started,
            VoiceSpeechBenchmarkConfiguration(
                mode, VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS.filter { it in backends }, voice,
                threads.coerceIn(1, 4), rate.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
                KokoroModelPack.MODEL_SHA256, TimedKokoro.RUNTIME_ID, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
                VoiceBackendCapabilityStore(context).deviceKey,
                workload = workload,
                backendOrderSeed = orderSeed,
                sourceCommit = BuildConfig.APP_COMMIT.takeIf { it.matches(Regex("[0-9a-f]{40}")) },
                playback = playback,
                playbackScenarios = playbackScenarios, calibrationPolicy = calibrationPolicy, captionOffsetMs = captionOffsetMs.coerceIn(-500, 500)
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
            val progress = normalized.completedChunks + normalized.playbackResults.size
            val persist = progress != persistedSamples || !normalized.isRunning
            val saved = if (persist) store.save(normalized) else mutable.value.saved
            if (persist) persistedSamples = progress
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
            val thermalStatus = { runCatching { thermal?.currentThermalStatus }.getOrNull() }
            if (playback) {
                val measurement = VoiceSpeechPlaybackBenchmark(context, models, report.configuration, thermalStatus)
                playbackRunner.set(measurement)
                if (cancelled.get()) measurement.cancel()
                for (backend in report.configuration.plannedBackendOrder) {
                    if (cancelled.get()) break
                    val index = report.results.indexOfFirst { it.requestedBackend == backend }
                    if ((thermalStatus() ?: 0) >= 2) {
                        report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                        break
                    }
                    val row = report.results[index]
                    val preceding = report.playbackResults.filterNot { it.requestedBackend == backend }
                    publish(report.copy(results = report.results.toMutableList().also { it[index] = row.copy(state = VoiceSpeechBenchmarkBackendState.RUNNING) }))
                    val results = try {
                        measurement.measureSeries(backend) { rows -> publish(report.copy(playbackResults = preceding + rows)) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: LinkageError) {
                        listOf(VoiceSpeechPlaybackResult(backend, VoiceSpeechPlaybackState.UNSUPPORTED, failure = dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechFailureCode.PROVIDER_LOAD))
                    } catch (error: Exception) {
                        val native = dev.chungjungsoo.gptmobile.data.voice.speechPlaybackFailureEvidence(error, backend, report.configuration.deviceKey)
                        listOf(VoiceSpeechPlaybackResult(backend, VoiceSpeechPlaybackState.FAILED, failure = native?.classification?.code ?: dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechFailureCode.EXECUTION_FAILED, failureStage = native?.classification?.stage, nativeCode = native?.classification?.nativeCode))
                    }
                    val failed = results.firstOrNull { it.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED) }
                    val backendState = when {
                        failed?.state == VoiceSpeechPlaybackState.UNSUPPORTED -> VoiceSpeechBenchmarkBackendState.UNSUPPORTED
                        failed != null -> VoiceSpeechBenchmarkBackendState.FAILED
                        cancelled.get() || results.any { it.state == VoiceSpeechPlaybackState.CANCELLED && it.cancellationTarget == null } -> VoiceSpeechBenchmarkBackendState.CANCELLED
                        else -> VoiceSpeechBenchmarkBackendState.COMPLETE
                    }
                    publish(
                        report.copy(
                            results = report.results.toMutableList().also { it[index] = row.copy(state = backendState, failure = failed?.failure, failureStage = failed?.failureStage) },
                            playbackResults = preceding + results
                        )
                    )
                    if (measurement.pendingRetirements.isNotEmpty()) {
                        retiringPlayback.set(measurement)
                        report = report.copy(issue = VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING)
                        mutable.update { it.copy(nativeCleanupPending = true, notice = "Audio has stopped. Native speech cleanup is still pending; new speech work stays paused until it finishes.") }
                        break
                    }
                    if (backendState == VoiceSpeechBenchmarkBackendState.CANCELLED) break
                }
                val finalState = when {
                    cancelled.get() || report.playbackResults.any { it.state == VoiceSpeechPlaybackState.CANCELLED && it.cancellationTarget == null } -> VoiceSpeechBenchmarkRunState.CANCELLED
                    report.issue != null -> VoiceSpeechBenchmarkRunState.INTERRUPTED
                    else -> VoiceSpeechBenchmarkRunState.COMPLETE
                }
                publish(report.copy(state = finalState, finishedAtMillis = System.currentTimeMillis()))
                return
            }
            val measurement = VoiceSpeechBenchmarkRunner(
                create = { backend ->
                    val factory = QualcommVoicePhraseEngineFactory(context, backend, {}, voice = voice, speechRate = report.configuration.speechRate)
                    pending.set(factory)
                    try {
                        factory.createBenchmarkEngine(models.modelDirectory(), voice.lexicon, report.configuration.threads)
                    } finally {
                        pending.compareAndSet(factory, null)
                    }
                },
                cancelPreparation = { pending.get()?.cancelPending() },
                thermalStatus = thermalStatus
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
            val retiring = playbackRunner.get()?.takeIf { it.pendingRetirements.isNotEmpty() }
            if (retiring != null) {
                retiringPlayback.set(retiring)
                mutable.update { it.copy(nativeCleanupPending = true) }
                if (leased) {
                    retirementScope.launch {
                        retiring.awaitRetired()
                        models.releaseUse()
                    }
                }
            } else if (leased) {
                models.releaseUse()
            }
        }
    }

    fun cancel() {
        if (!mutable.value.running) return
        cancelled.set(true)
        mutable.update { it.copy(cancelling = true) }
        runner.get()?.cancel()
        playbackRunner.get()?.cancel()
    }

    override fun onCleared() {
        cancelled.set(true)
        runner.get()?.cancel()
        playbackRunner.get()?.cancel()
        super.onCleared()
    }

    companion object {
        private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val benchmarkLease = VoiceSpeechBenchmarkOwnership()
        val audioOwnership = benchmarkLease.state
    }
}
