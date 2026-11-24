package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.QualcommVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceBackendCapabilityStore
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBackendState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkEngine
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkIssue
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkMode
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkRunState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkRunner
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkWorkload
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechCalibrationPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechEngineBenchmarkResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechEngineBenchmarkRunner
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackBenchmark
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackScenario
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackState
import dev.chungjungsoo.gptmobile.data.voice.mediaPipeEngine
import dev.melo.kokoro.TimedKokoro
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.EmptyCoroutineContext
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

@HiltViewModel
internal class VoiceSpeechBenchmarkViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val mutable = MutableStateFlow(VoiceSpeechBenchmarkUiState().withQuarantine(benchmarkLease.quarantineReason.value))
    val state = mutable.asStateFlow()
    private val runner = AtomicReference<VoiceSpeechBenchmarkRunner?>(null)
    private val engineRunner = AtomicReference<VoiceSpeechEngineBenchmarkRunner?>(null)
    private val streamingModels = MediaPipeSpeechModelStore(context, viewModelScope)
    private val cleanupQuarantined = AtomicBoolean()
    private val playbackRunner = AtomicReference<VoiceSpeechPlaybackBenchmark?>(null)
    private val cancelled = AtomicBoolean(false)
    private val retiringPlayback = AtomicReference<VoiceSpeechPlaybackBenchmark?>(null)
    private var job: Job? = null

    init {
        viewModelScope.launch {
            benchmarkLease.quarantineReason.collect { reason ->
                if (reason != null) mutable.update { it.withQuarantine(reason) }
            }
        }
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { runCatching { VoiceSpeechBenchmarkStore(context).read() }.getOrDefault(emptyList()) }
            mutable.update { it.copy(loaded = true, history = history, report = history.firstOrNull()) }
            refreshEngines()
        }
    }

    fun refreshEngines() {
        if (mutable.value.running || mutable.value.nativeCleanupPending) return
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) {
                buildSet {
                    val native = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
                    runCatching { native.refresh() }
                    if (native.isInstalled()) add(VoiceSpeechBenchmarkEngine.NATIVE_KOKORO)
                    runCatching { streamingModels.refresh() }
                    VoiceSpeechBenchmarkEngine.entries.forEach { engine ->
                        engine.mediaPipeEngine()?.let { if (streamingModels.isReady(it)) add(engine) }
                    }
                }
            }
            mutable.update { it.copy(availableEngines = available) }
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
        captionOffsetMs: Int = 0,
        engine: VoiceSpeechBenchmarkEngine = VoiceSpeechBenchmarkEngine.NATIVE_KOKORO,
        deepDiagnostics: Boolean = false
    ) {
        benchmarkLease.quarantineReason.value?.let { reason ->
            mutable.update { it.withQuarantine(reason) }
            return
        }
        if (!mutable.value.loaded || mutable.value.running || mutable.value.nativeCleanupPending || backends.isEmpty() || backends.any { it !in engine.supportedBackends }) return
        val lease = benchmarkLease.acquire()
        if (lease == null) {
            mutable.update { current ->
                val reason = benchmarkLease.quarantineReason.value
                if (reason == null) current.copy(notice = "Another speech benchmark is finishing. Try again shortly.") else current.withQuarantine(reason)
            }
            return
        }
        cancelled.set(false)
        mutable.update { it.copy(running = true, cancelling = false, saved = true, notice = null, report = null, nativeCleanupPending = false) }
        job = viewModelScope.launch(ConversationPrivacyContext.capture() ?: EmptyCoroutineContext) {
            try {
                withContext(Dispatchers.IO) { execute(mode, backends, voice, threads, rate, workload, playback, playbackScenarios, calibrationPolicy, captionOffsetMs, engine, deepDiagnostics, lease) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(notice = "The speech benchmark could not start. Try again.") }
            } catch (_: LinkageError) {
                mutable.update { it.copy(notice = "The speech runtime could not be loaded. Repair the installed voice model and try again.") }
            } finally {
                runner.set(null)
                engineRunner.set(null)
                playbackRunner.set(null)
                mutable.update { it.copy(running = false, cancelling = false) }
            }
        }
        job?.invokeOnCompletion {
            if (cleanupQuarantined.get()) return@invokeOnCompletion
            val retiring = retiringPlayback.get()
            if (retiring == null) {
                mutable.update { it.copy(nativeCleanupPending = false) }
                lease.release()
            } else {
                retirementScope.launch {
                    val released = lease.releaseAfterRetirement(retiring::awaitRetired, { retiring.nativeCleanupPending }) {
                        cleanupQuarantined.set(true)
                        synchronized(failedCleanupOwners) { failedCleanupOwners += retiring }
                        mutable.update { it.withQuarantine(benchmarkLease.quarantineReason.value) }
                    }
                    if (released) {
                        retiringPlayback.compareAndSet(retiring, null)
                        mutable.update { it.copy(nativeCleanupPending = false, notice = "Native speech cleanup finished. You can start another test.") }
                    }
                }
            }
        }
    }

    private suspend fun execute(mode: VoiceSpeechBenchmarkMode, backends: List<VoiceSpeechBackend>, voice: KokoroVoice, threads: Int, rate: Float, workload: VoiceSpeechBenchmarkWorkload, playback: Boolean, playbackScenarios: List<VoiceSpeechPlaybackScenario>, calibrationPolicy: VoiceSpeechCalibrationPolicy, captionOffsetMs: Int, engine: VoiceSpeechBenchmarkEngine, deepDiagnostics: Boolean, ownerLease: VoiceSpeechBenchmarkOwnership.Lease) {
        val store = VoiceSpeechBenchmarkStore(context)
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        val sdkEngine = engine.mediaPipeEngine()
        val sdkModel = sdkEngine?.let(MediaPipeSpeechCatalog::model)
        val nativeEngine = sdkEngine == null
        val started = System.currentTimeMillis()
        val previousOrder = mutable.value.history.firstOrNull()?.configuration?.backendOrderSeed
        val orderSeed = previousOrder?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: 0L
        var report = VoiceSpeechBenchmarkReport(
            started,
            VoiceSpeechBenchmarkConfiguration(
                mode, engine.supportedBackends.filter { it in backends }, voice,
                threads.coerceIn(1, 4), rate.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
                sdkModel?.sha256 ?: KokoroModelPack.MODEL_SHA256, if (nativeEngine) TimedKokoro.RUNTIME_ID else MediaPipeSpeechCatalog.RUNTIME_ID, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
                VoiceBackendCapabilityStore(context).deviceKey,
                workload = workload,
                backendOrderSeed = orderSeed,
                sourceCommit = BuildConfig.APP_COMMIT.takeIf { it.matches(Regex("[0-9a-f]{40}")) },
                playback = playback,
                playbackScenarios = playbackScenarios, calibrationPolicy = calibrationPolicy, captionOffsetMs = captionOffsetMs.coerceIn(-500, 500),
                engine = engine, engineVoiceId = sdkModel?.defaultVoice ?: voice.name, deepDiagnostics = deepDiagnostics
            ),
            engineResults = if (nativeEngine) emptyList() else backends.map(::VoiceSpeechEngineBenchmarkResult)
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
                    },
                    engineResults = value.engineResults.map { result ->
                        if (result.state in setOf(VoiceSpeechBenchmarkBackendState.PENDING, VoiceSpeechBenchmarkBackendState.PREPARING, VoiceSpeechBenchmarkBackendState.RUNNING)) result.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN) else result
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
                val proceeding = !cancelled.get() && normalized.engineResults.any { it.state == VoiceSpeechBenchmarkBackendState.PREPARING }
                current.copy(
                    report = normalized,
                    saved = saved,
                    cancelling = if (proceeding) false else current.cancelling,
                    notice = if (proceeding && current.cancelling) null else current.notice,
                    history = (listOf(normalized) + current.history.filterNot { it.startedAtMillis == normalized.startedAtMillis }).take(5)
                )
            }
        }
        var leased = false
        val pending = AtomicReference<QualcommVoicePhraseEngineFactory?>(null)
        try {
            if (cancelled.get()) throw CancellationException("Cancelled")
            if (nativeEngine) leased = models.acquireUse() else streamingModels.refresh(sdkEngine)
            if (nativeEngine && !leased || sdkEngine != null && !streamingModels.isReady(sdkEngine)) {
                publish(report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE, finishedAtMillis = System.currentTimeMillis()))
                return
            }
            val thermal = context.getSystemService(PowerManager::class.java)
            val thermalStatus = { runCatching { thermal?.currentThermalStatus }.getOrNull() }
            val sdkFactory: (VoiceSpeechBackend) -> VoicePhraseEngineFactory = { backend ->
                MediaPipeVoicePhraseEngineFactory(streamingModels, checkNotNull(sdkEngine), backend, report.configuration.speechRate, checkNotNull(sdkModel).defaultVoice)
            }
            if (playback) {
                val measurement = VoiceSpeechPlaybackBenchmark(context, models, report.configuration, thermalStatus, engineFactory = sdkFactory.takeUnless { nativeEngine })
                playbackRunner.set(measurement)
                if (cancelled.get()) measurement.cancel()
                for (backend in report.configuration.plannedBackendOrder) {
                    if (cancelled.get()) break
                    val index = if (nativeEngine) report.results.indexOfFirst { it.requestedBackend == backend } else report.engineResults.indexOfFirst { it.requestedBackend == backend }
                    if ((thermalStatus() ?: 0) >= 2) {
                        report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                        break
                    }
                    val row = report.results.getOrNull(index)
                    val sdkRow = report.engineResults.getOrNull(index)
                    fun backendReport(state: VoiceSpeechBenchmarkBackendState, failed: VoiceSpeechPlaybackResult? = null, cleanup: String = failed?.cleanupState ?: "NOT_OBSERVED"): VoiceSpeechBenchmarkReport = if (nativeEngine) {
                        report.copy(results = report.results.toMutableList().also { it[index] = checkNotNull(row).copy(state = state, failure = failed?.failure, failureStage = failed?.failureStage) })
                    } else {
                        report.copy(engineResults = report.engineResults.toMutableList().also { it[index] = checkNotNull(sdkRow).copy(state = state, failureCode = failed?.pipelineFailureCode ?: failed?.failure?.name, cleanupState = cleanup) })
                    }
                    val preceding = report.playbackResults.filterNot { it.requestedBackend == backend }
                    publish(backendReport(VoiceSpeechBenchmarkBackendState.RUNNING))
                    val observations = try {
                        measurement.measureSeries(backend) { rows -> publish(report.copy(playbackResults = preceding + rows)) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: LinkageError) {
                        listOf(VoiceSpeechPlaybackResult(backend, VoiceSpeechPlaybackState.UNSUPPORTED, failure = dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechFailureCode.PROVIDER_LOAD))
                    } catch (error: Exception) {
                        val native = dev.chungjungsoo.gptmobile.data.voice.speechPlaybackFailureEvidence(error, backend, report.configuration.deviceKey)
                        listOf(VoiceSpeechPlaybackResult(backend, VoiceSpeechPlaybackState.FAILED, failure = native?.classification?.code ?: dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechFailureCode.EXECUTION_FAILED, failureStage = native?.classification?.stage, nativeCode = native?.classification?.nativeCode))
                    }
                    val results = if (nativeEngine) {
                        observations
                    } else {
                        observations.map { value ->
                            value.copy(requestedDelegate = dev.chungjungsoo.gptmobile.data.voice.requestedSpeechDelegate(backend), observedProviderStatus = "NOT_EXPOSED_BY_SDK", requestedThreads = null, effectiveThreads = null)
                        }
                    }
                    val failed = results.firstOrNull { it.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED) }
                    val backendState = when {
                        failed?.state == VoiceSpeechPlaybackState.UNSUPPORTED -> VoiceSpeechBenchmarkBackendState.UNSUPPORTED
                        failed != null -> VoiceSpeechBenchmarkBackendState.FAILED
                        cancelled.get() || results.any { it.state == VoiceSpeechPlaybackState.CANCELLED && it.cancellationTarget == null } -> VoiceSpeechBenchmarkBackendState.CANCELLED
                        else -> VoiceSpeechBenchmarkBackendState.COMPLETE
                    }
                    publish(
                        backendReport(
                            backendState,
                            failed,
                            when {
                                results.any { it.cleanupState == "FAILED" } -> "FAILED"
                                results.isNotEmpty() && results.all { it.cleanupState == "RETIRED" } -> "RETIRED"
                                else -> "NOT_OBSERVED"
                            }
                        ).copy(playbackResults = preceding + results)
                    )
                    if (measurement.pendingRetirements.isNotEmpty() || measurement.nativeCleanupPending) {
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
            if (!nativeEngine) {
                val measurement = VoiceSpeechEngineBenchmarkRunner(
                    sdkFactory,
                    context.noBackupFilesDir,
                    thermalStatus,
                    onStopping = { mutable.update { it.copy(cancelling = true, notice = "The speech time limit was reached. Waiting for the native owner to stop safely.") } }
                )
                engineRunner.set(measurement)
                if (cancelled.get()) measurement.cancel()
                measurement.run(report, ::publish)
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
            val unconfirmed = runner.get()?.takeIf { it.nativeCleanupPending }
                ?: engineRunner.get()?.takeIf { it.nativeCleanupPending }
                ?: playbackRunner.get()?.takeIf { it.nativeCleanupPending }
            // Always capture the owner. A destructor can fail between the failure check
            // above and an empty pending-retirement observation; only await + recheck
            // may authorize release of the model or process-wide speech lease.
            val retiring = playbackRunner.get()
            if (unconfirmed != null) {
                cleanupQuarantined.set(true)
                ownerLease.quarantine()
                synchronized(failedCleanupOwners) {
                    failedCleanupOwners += unconfirmed
                    if (leased) failedCleanupOwners += models
                }
                mutable.update { it.withQuarantine(benchmarkLease.quarantineReason.value) }
            } else if (retiring != null) {
                retiringPlayback.set(retiring)
                mutable.update { it.copy(nativeCleanupPending = true) }
                if (leased) {
                    retirementScope.launch {
                        retiring.awaitRetired()
                        if (retiring.nativeCleanupPending) {
                            cleanupQuarantined.set(true)
                            ownerLease.quarantine()
                            synchronized(failedCleanupOwners) {
                                failedCleanupOwners += models
                                failedCleanupOwners += retiring
                            }
                        } else {
                            models.releaseUse()
                        }
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
        engineRunner.get()?.cancel()
        playbackRunner.get()?.cancel()
    }

    override fun onCleared() {
        cancelled.set(true)
        runner.get()?.cancel()
        engineRunner.get()?.cancel()
        playbackRunner.get()?.cancel()
        super.onCleared()
    }

    companion object {
        private val failedCleanupOwners = mutableListOf<Any>()
        private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val benchmarkLease = VoiceSpeechBenchmarkOwnership()
        val audioOwnership = benchmarkLease.state
    }
}
