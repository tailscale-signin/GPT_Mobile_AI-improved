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
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.QualcommVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceBackendCapabilityStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchManifest
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchObservation
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchProbes
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchStage
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchmarkCampaign
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchmarkSuiteStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceCpuSchedulingPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceSnapshot
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBackendState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkBatterySource
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkEnergyTracker
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkEngine
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkIssue
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkRunState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkRunner
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechEngineBenchmarkResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechEngineBenchmarkRunner
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackBenchmark
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackPass
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackProtocol
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackResult
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechPlaybackState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechStopOrigin
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import dev.chungjungsoo.gptmobile.data.voice.fullVoiceBenchPlan
import dev.chungjungsoo.gptmobile.data.voice.mediaPipeEngine
import dev.chungjungsoo.gptmobile.data.voice.suiteResult
import dev.chungjungsoo.gptmobile.data.voice.summarize
import dev.chungjungsoo.gptmobile.data.voice.writeVoiceSpeechBenchmarkExport
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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
    private val stopOrigin = AtomicReference<VoiceSpeechStopOrigin?>(null)
    private val persistRetirement = AtomicReference<(() -> Unit)?>(null)
    private val fullStore = VoiceBenchmarkSuiteStore(File(context.noBackupFilesDir, "voice-full-benchmarks"))
    private val probes = AtomicReference<VoiceBenchProbes?>(null)
    private var job: Job? = null

    init {
        viewModelScope.launch {
            benchmarkLease.quarantineReason.collect { reason ->
                if (reason != null) mutable.update { it.withQuarantine(reason) }
            }
        }
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { runCatching { VoiceSpeechBenchmarkStore(context).read() }.getOrDefault(emptyList()) }
            val suite = withContext(Dispatchers.IO) { fullStore.latest(active = benchmarkLease.state.value != null && benchmarkLease.quarantineReason.value == null) }
            mutable.update { it.copy(loaded = true, history = history, report = history.firstOrNull(), suite = suite) }
        }
    }

    fun selectHistory(index: Int) {
        if (!mutable.value.running) mutable.value.history.getOrNull(index)?.let { report -> mutable.update { it.copy(report = report) } }
    }

    fun startFull(voice: KokoroVoice, threads: Int, rate: Float, captionOffsetMs: Int, streamingVoice: String?, performance: VoicePerformanceSnapshot, feedback: VoiceSynthesisFeedback?, gateway: VoicePhraseEngineFactory?, beforeStart: () -> Unit = {}) {
        if (!mutable.value.loaded || mutable.value.running || mutable.value.nativeCleanupPending) return
        val lease = benchmarkLease.acquire()
        if (lease == null) {
            mutable.update { it.copy(notice = "Another voice benchmark owns the audio device.").withQuarantine(benchmarkLease.quarantineReason.value) }
            return
        }
        try {
            beforeStart()
        } catch (_: Exception) {
            lease.release()
            mutable.update { it.copy(notice = "The active voice session could not release audio.") }
            return
        }
        stopOrigin.set(null)
        cancelled.set(false)
        mutable.update { it.copy(running = true, cancelling = false, saved = true, notice = null, report = null, suite = null) }
        job = viewModelScope.launch(ConversationPrivacyContext.capture() ?: EmptyCoroutineContext) {
            try {
                withContext(Dispatchers.IO) { runFull(voice, threads, rate, captionOffsetMs, streamingVoice, performance, feedback, gateway, lease) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(saved = false, notice = "The benchmark was interrupted. Its last saved checkpoint is available.") }
            } catch (_: LinkageError) {
                mutable.update { it.copy(notice = "A voice runtime could not be loaded.") }
            }
        }
        job?.invokeOnCompletion {
            if (!cleanupQuarantined.get()) lease.release()
            mutable.update { it.copy(running = false, cancelling = false).withQuarantine(benchmarkLease.quarantineReason.value) }
        }
    }

    private suspend fun runFull(voice: KokoroVoice, threads: Int, rate: Float, offset: Int, streamingVoice: String?, performance: VoicePerformanceSnapshot, feedback: VoiceSynthesisFeedback?, gateway: VoicePhraseEngineFactory?, lease: VoiceSpeechBenchmarkOwnership.Lease) {
        val plan = fullVoiceBenchPlan(threads, rate, VoiceCpuSchedulingPolicy.deviceLimit())
        val started = System.currentTimeMillis()
        val id = "$started-${UUID.randomUUID()}"
        val native = KokoroModelVariant.entries.filter { variant ->
            val model = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"), variant = variant)
            model.refresh(false)
            model.isInstalled()
        }
        streamingModels.refresh(forceVerification = false)
        val rows = plan.map { stage ->
            val missing = when (stage.engine) {
                null -> false
                VoiceSpeechBenchmarkEngine.NATIVE_KOKORO -> stage.variant !in native
                else -> !streamingModels.isReady(requireNotNull(stage.engine.mediaPipeEngine()))
            }
            if (missing) VoiceBenchResult(stage.label, VoiceBenchStatus.UNAVAILABLE, "MODEL_NOT_INSTALLED") else VoiceBenchResult(stage.label)
        }
        val rotation = fullStore.latest()?.backendOrderSeed?.takeIf { it < Long.MAX_VALUE - plan.size }?.plus(1) ?: 0L
        val manifest = VoiceBenchManifest(backendOrderSeed = rotation, runId = id, startedAtMillis = started, build = BuildConfig.VERSION_CODE, commit = BuildConfig.APP_COMMIT, androidApi = Build.VERSION.SDK_INT, results = rows)
        fullStore.begin(manifest, performance, feedback)
        mutable.update { it.copy(suite = manifest) }
        val deviceProbes = VoiceBenchProbes(context, streamingModels, gateway, voice, { !cancelled.get() }) { owner -> quarantine(lease, owner) }
        probes.set(deviceProbes)
        val campaign = VoiceBenchmarkCampaign(fullStore, { updated -> mutable.update { it.copy(suite = updated) } })
        try {
            campaign.run(manifest, plan, {
                when {
                    cleanupQuarantined.get() -> "NATIVE_CLEANUP_UNCONFIRMED"
                    cancelled.get() -> if (stopOrigin.get() == VoiceSpeechStopOrigin.USER) "CANCELLED" else "INTERRUPTED"
                    else -> null
                }
            }, ::coolEnough) { index, stage ->
                mutable.update { it.copy(report = null) }
                if (stage.probe != null) {
                    measureProbe(id, index, stage, deviceProbes)
                } else {
                    fun save(report: VoiceSpeechBenchmarkReport) = fullStore.evidence(id, index) { writeVoiceSpeechBenchmarkExport(report, it) }
                    val config = stage.configuration(voice, streamingVoice, offset, rotation + index, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, VoiceBackendCapabilityStore(context).deviceKey, BuildConfig.APP_COMMIT)
                    val report = execute(config, lease, ::save)
                    save(report)
                    report.suiteResult(stage.label)
                }
            }
        } finally {
            probes.compareAndSet(deviceProbes, null)
        }
    }

    private suspend fun coolEnough(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        for (attempt in 0..60) {
            if (cancelled.get() || cleanupQuarantined.get()) return false
            if ((runCatching { power?.currentThermalStatus }.getOrNull() ?: 0) < PowerManager.THERMAL_STATUS_MODERATE) {
                if (attempt > 0) mutable.update { it.copy(notice = null) }
                return true
            }
            mutable.update { it.copy(notice = "Waiting for the phone to cool before the next measurement.") }
            if (attempt < 60) delay(5_000L)
        }
        return false
    }

    private suspend fun measureProbe(id: String, index: Int, stage: VoiceBenchStage, device: VoiceBenchProbes): VoiceBenchResult {
        var observed = emptyList<VoiceBenchObservation>()
        fun save(rows: List<VoiceBenchObservation>) {
            observed = rows.toList()
            fullStore.evidence(id, index) { it.write(Json.encodeToString(observed).toByteArray()) }
        }
        val rows = try {
            device.run(stage, ::save)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            observed + VoiceBenchObservation(stage.probe!!.name, VoiceBenchStatus.FAILED, "DEVICE_PROBE_FAILED")
        } catch (_: LinkageError) {
            observed + VoiceBenchObservation.unavailable(stage.probe!!.name, "NATIVE_RUNTIME_UNAVAILABLE")
        }
        save(rows)
        return rows.summarize(stage.label)
    }

    fun exportSuite(suite: VoiceBenchManifest, output: OutputStream) = fullStore.export(suite, output)

    private suspend fun execute(configuration: VoiceSpeechBenchmarkConfiguration, ownerLease: VoiceSpeechBenchmarkOwnership.Lease, checkpoint: (VoiceSpeechBenchmarkReport) -> Unit): VoiceSpeechBenchmarkReport {
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"), variant = configuration.nativeModelVariant)
        val sdkEngine = configuration.engine.mediaPipeEngine()
        val nativeEngine = sdkEngine == null
        val initialReport = VoiceSpeechBenchmarkReport(System.currentTimeMillis(), configuration, engineResults = if (nativeEngine) emptyList() else configuration.backends.map(::VoiceSpeechEngineBenchmarkResult))
        val writer = BenchmarkReportWriter(initialReport, checkpoint)
        var leased = false
        try {
            if (cancelled.get()) throw CancellationException("Cancelled")
            if (nativeEngine) leased = models.acquireUse() else streamingModels.refresh(sdkEngine, false)
            if (nativeEngine && !leased || sdkEngine != null && !streamingModels.isReady(sdkEngine)) {
                writer.publish(writer.report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE, finishedAtMillis = System.currentTimeMillis()))
                return writer.report
            }
            val thermal = context.getSystemService(PowerManager::class.java)
            val thermalStatus = { runCatching { thermal?.currentThermalStatus }.getOrNull() }
            val sdkFactory: (VoiceSpeechBackend) -> VoicePhraseEngineFactory = { backend ->
                val configuration = writer.report.configuration
                MediaPipeVoicePhraseEngineFactory(streamingModels, checkNotNull(sdkEngine), backend, configuration.speechRate, checkNotNull(configuration.engineVoiceId), kokoroBucket = configuration.kokoroBucket, incrementalInput = configuration.incrementalInput)
            }
            when {
                configuration.playback -> {
                    val config = writer.report.configuration
                    val pins = sdkEngine?.let { streamingModels.retainComparisonCaches(it, config.backends, 1f, if (it.isKokoro) config.kokoroBucket.sdkValue else 0) }
                    try {
                        measurePlayback(models, writer, thermalStatus, sdkFactory.takeUnless { nativeEngine })
                    } finally {
                        pins?.close()
                    }
                }
                nativeEngine -> measureNative(models, writer, thermalStatus)
                else -> measureSdk(writer, thermalStatus, sdkFactory)
            }
        } catch (_: CancellationException) {
            writer.publish(writer.report.copy(state = VoiceSpeechBenchmarkRunState.CANCELLED, issue = VoiceSpeechBenchmarkIssue.CANCELLED, finishedAtMillis = System.currentTimeMillis()))
        } catch (_: Exception) {
            writer.publish(writer.report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.EXECUTION_FAILED, finishedAtMillis = System.currentTimeMillis()))
        } catch (_: LinkageError) {
            writer.publish(writer.report.copy(state = VoiceSpeechBenchmarkRunState.FAILED, issue = VoiceSpeechBenchmarkIssue.EXECUTION_FAILED, finishedAtMillis = System.currentTimeMillis()))
        } finally {
            try {
                writer.finish()
            } finally {
                withContext(NonCancellable) { releaseModelsAfterRun(models, leased, ownerLease) }
            }
        }
        return writer.report
    }

    /** Keep publication out of the suspend state machines and preserve a single energy owner. */
    private inner class BenchmarkReportWriter(var report: VoiceSpeechBenchmarkReport, private val checkpoint: (VoiceSpeechBenchmarkReport) -> Unit) {
        private var lastSavedMs = 0L
        private var savedSamples = 0
        private val batterySource = VoiceSpeechBenchmarkBatterySource(context)
        private val energy = VoiceSpeechBenchmarkEnergyTracker(batterySource::begin)

        fun publish(value: VoiceSpeechBenchmarkReport) {
            val terminal = if (value.state == VoiceSpeechBenchmarkRunState.CANCELLED) {
                value.copy(stopOrigin = value.stopOrigin ?: stopOrigin.get() ?: VoiceSpeechStopOrigin.COROUTINE)
            } else {
                value
            }
            report = energy.attach(if (terminal.isRunning) terminal else terminal.finishPendingBackends())
            mutable.update { it.copy(report = report) }
            // A partial stage survives process death. Throttle serialization to completed samples.
            val now = android.os.SystemClock.elapsedRealtime()
            if (report.isRunning && report.completedSamples > savedSamples && (savedSamples == 0 || now - lastSavedMs >= 60_000L)) {
                checkpoint(report)
                lastSavedMs = now
                savedSamples = report.completedSamples
            }
        }
        fun finish() {
            try {
                if (report.isRunning) publish(report.copy(state = VoiceSpeechBenchmarkRunState.INTERRUPTED, finishedAtMillis = System.currentTimeMillis()))
            } finally {
                energy.close()
            }
        }
    }

    private suspend fun measurePlayback(
        models: LocalVoiceModelStore,
        writer: BenchmarkReportWriter,
        thermalStatus: () -> Int?,
        sdkFactory: ((VoiceSpeechBackend) -> VoicePhraseEngineFactory)?
    ) {
        val nativeEngine = writer.report.configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO
        val measurement = VoiceSpeechPlaybackBenchmark(context, models, writer.report.configuration, thermalStatus, engineFactory = sdkFactory)
        playbackRunner.set(measurement)
        if (cancelled.get()) measurement.cancel(stopOrigin.get() ?: VoiceSpeechStopOrigin.COROUTINE)
        val failedBackends = mutableSetOf<VoiceSpeechBackend>()
        for (pass in VoiceSpeechPlaybackProtocol.passes(writer.report.configuration)) {
            if (cancelled.get()) break
            val backend = pass.backend
            if (backend in failedBackends) continue
            if ((thermalStatus() ?: 0) >= 2) {
                writer.report = writer.report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                break
            }
            val preceding = writer.report.playbackResults
            publishPlaybackBackend(writer, backend, VoiceSpeechBenchmarkBackendState.RUNNING)
            val (observations, passFailure) = measurePlaybackBackend(measurement, pass, writer, preceding)
            val results = if (nativeEngine) {
                observations
            } else {
                observations.map { value ->
                    value.copy(requestedDelegate = dev.chungjungsoo.gptmobile.data.voice.requestedSpeechDelegate(backend), observedProviderStatus = "NOT_EXPOSED_BY_SDK", requestedThreads = null, effectiveThreads = null)
                }
            }
            val failed = passFailure ?: results.firstOrNull { it.state in setOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED) }
            if (failed != null) failedBackends += backend
            val backendState = when {
                failed?.state == VoiceSpeechPlaybackState.UNSUPPORTED -> VoiceSpeechBenchmarkBackendState.UNSUPPORTED
                failed != null || measurement.thermalLimitReached -> VoiceSpeechBenchmarkBackendState.FAILED
                cancelled.get() || results.any { it.state == VoiceSpeechPlaybackState.CANCELLED && it.cancellationTarget == null } -> VoiceSpeechBenchmarkBackendState.CANCELLED
                !pass.completesBackend || results.isEmpty() -> VoiceSpeechBenchmarkBackendState.PARTIAL
                else -> VoiceSpeechBenchmarkBackendState.COMPLETE
            }
            val cleanup = when {
                results.any { it.cleanupState == "FAILED" } -> "FAILED"
                results.isNotEmpty() && results.all { it.cleanupState == "RETIRED" } -> "RETIRED"
                else -> "NOT_OBSERVED"
            }
            publishPlaybackBackend(writer, backend, backendState, failed, cleanup, preceding + results)
            // Native ownership takes priority even when thermal pressure ended the work.
            if (results.any { it.cleanupState == "PENDING" } || measurement.pendingRetirements.isNotEmpty() || measurement.nativeCleanupPending) {
                persistRetirement.set { writer.publish(measurement.confirmedCleanup(writer.report)) }
                writer.report = writer.report.copy(issue = VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING)
                mutable.update { it.copy(nativeCleanupPending = true, notice = "Audio has stopped. Native speech cleanup is still pending; new speech work stays paused until it finishes.") }
                break
            }
            if (measurement.thermalLimitReached) {
                writer.report = writer.report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                break
            }
            if (backendState == VoiceSpeechBenchmarkBackendState.CANCELLED) break
        }
        val report = writer.report
        val finalState = when {
            cancelled.get() || report.playbackResults.any { it.state == VoiceSpeechPlaybackState.CANCELLED && it.cancellationTarget == null } -> VoiceSpeechBenchmarkRunState.CANCELLED
            report.issue != null -> VoiceSpeechBenchmarkRunState.INTERRUPTED
            else -> VoiceSpeechBenchmarkRunState.COMPLETE
        }
        writer.publish(report.copy(state = finalState, finishedAtMillis = System.currentTimeMillis()))
    }

    private data class PlaybackPassResult(val rows: List<VoiceSpeechPlaybackResult>, val failure: VoiceSpeechPlaybackResult? = null)

    private suspend fun measurePlaybackBackend(
        measurement: VoiceSpeechPlaybackBenchmark,
        pass: VoiceSpeechPlaybackPass,
        writer: BenchmarkReportWriter,
        preceding: List<VoiceSpeechPlaybackResult>
    ): PlaybackPassResult {
        val backend = pass.backend
        val failure = try {
            val measured = measurement.measureSeries(backend, pass.plans) { rows -> writer.publish(writer.report.copy(playbackResults = preceding + rows)) }
            return PlaybackPassResult(measured)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            VoiceSpeechPlaybackResult(backend, VoiceSpeechPlaybackState.UNSUPPORTED, failure = dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechFailureCode.PROVIDER_LOAD)
        } catch (error: Exception) {
            val configuration = writer.report.configuration
            val native = dev.chungjungsoo.gptmobile.data.voice.speechPlaybackFailureEvidence(error, backend, configuration.deviceKey, configuration.modelSha256)
            VoiceSpeechPlaybackResult(backend, VoiceSpeechPlaybackState.FAILED, failure = native?.classification?.code ?: dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechFailureCode.EXECUTION_FAILED, failureStage = native?.classification?.stage, nativeCode = native?.classification?.nativeCode)
        }
        val retained = writer.report.playbackResults.drop(preceding.size)
        val plan = pass.plans.first()
        return PlaybackPassResult(
            retained.ifEmpty {
                listOf(failure.copy(scenario = plan.scenario, repeat = plan.repeat, warmup = plan.warmup, expectedResident = plan.expectedResident, fixtureHash = plan.manifest.hash, cancellationTarget = plan.cancellationTarget))
            },
            failure
        )
    }

    private fun publishPlaybackBackend(
        writer: BenchmarkReportWriter,
        backend: VoiceSpeechBackend,
        state: VoiceSpeechBenchmarkBackendState,
        failed: VoiceSpeechPlaybackResult? = null,
        cleanup: String = failed?.cleanupState ?: "NOT_OBSERVED",
        results: List<VoiceSpeechPlaybackResult> = writer.report.playbackResults
    ) {
        val report = writer.report
        val updated = if (report.configuration.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO) {
            report.copy(
                results = report.results.map { row ->
                    if (row.requestedBackend == backend) row.copy(state = state, failure = failed?.failure, failureStage = failed?.failureStage) else row
                }
            )
        } else {
            report.copy(
                engineResults = report.engineResults.map { row ->
                    if (row.requestedBackend == backend) row.copy(state = state, failureCode = failed?.pipelineFailureCode ?: failed?.failure?.name, cleanupState = cleanup) else row
                }
            )
        }
        writer.publish(updated.copy(playbackResults = results))
    }

    private fun measureSdk(writer: BenchmarkReportWriter, thermalStatus: () -> Int?, sdkFactory: (VoiceSpeechBackend) -> VoicePhraseEngineFactory) {
        val measurement = VoiceSpeechEngineBenchmarkRunner(
            sdkFactory,
            context.noBackupFilesDir,
            thermalStatus,
            onStopping = { mutable.update { it.copy(notice = "A speech measurement timed out. Waiting for its runtime to finish.") } }
        )
        engineRunner.set(measurement)
        if (cancelled.get()) measurement.cancel()
        measurement.run(writer.report, writer::publish)
    }

    private fun measureNative(models: LocalVoiceModelStore, writer: BenchmarkReportWriter, thermalStatus: () -> Int?) {
        val configuration = writer.report.configuration
        val pending = AtomicReference<QualcommVoicePhraseEngineFactory?>(null)
        val failedPreparation = AtomicReference<QualcommVoicePhraseEngineFactory?>(null)
        val measurement = VoiceSpeechBenchmarkRunner(
            create = { backend ->
                val factory = QualcommVoicePhraseEngineFactory(context, backend, {}, voice = configuration.voice, speechRate = configuration.speechRate, htpPerformanceMode = configuration.htpPerformanceMode, modelVariant = configuration.nativeModelVariant)
                pending.set(factory)
                try {
                    factory.createBenchmarkEngine(models.modelDirectory(), configuration.voice.lexicon, configuration.threads)
                } finally {
                    if (factory.nativeCleanupPending) failedPreparation.compareAndSet(null, factory)
                    pending.compareAndSet(factory, null)
                }
            },
            cancelPreparation = { pending.get()?.cancelPending() },
            preparationOwner = failedPreparation::get,
            thermalStatus = thermalStatus
        )
        runner.set(measurement)
        if (cancelled.get()) measurement.cancel()
        measurement.run(writer.report, writer::publish)
    }

    private fun quarantine(lease: VoiceSpeechBenchmarkOwnership.Lease, owner: Any) {
        cleanupQuarantined.set(true)
        lease.quarantine()
        synchronized(failedCleanupOwners) { failedCleanupOwners += owner }
        mutable.update { it.withQuarantine(benchmarkLease.quarantineReason.value) }
    }

    private suspend fun releaseModelsAfterRun(models: LocalVoiceModelStore, leased: Boolean, ownerLease: VoiceSpeechBenchmarkOwnership.Lease) {
        val playback = playbackRunner.get()
        try {
            if (playback != null) playback.awaitRetired()
            val failed = runner.get()?.takeIf { it.nativeCleanupPending } ?: engineRunner.get()?.takeIf { it.nativeCleanupPending } ?: playback?.takeIf { it.nativeCleanupPending }
            if (failed != null) {
                quarantine(ownerLease, listOfNotNull(failed, models.takeIf { leased }))
            } else {
                if (leased) models.releaseUse()
                persistRetirement.getAndSet(null)?.invoke()
                mutable.update { it.copy(nativeCleanupPending = false, notice = null) }
            }
        } catch (error: Throwable) {
            quarantine(ownerLease, listOfNotNull(playback, models.takeIf { leased }))
            throw error
        } finally {
            runner.set(null)
            engineRunner.set(null)
            playbackRunner.set(null)
        }
    }

    fun cancel() {
        if (!mutable.value.running) return
        stopOrigin.compareAndSet(null, VoiceSpeechStopOrigin.USER)
        cancelled.set(true)
        mutable.update { it.copy(cancelling = true) }
        probes.get()?.cancel()
        runner.get()?.cancel()
        engineRunner.get()?.cancel()
        playbackRunner.get()?.cancel(stopOrigin.get() ?: VoiceSpeechStopOrigin.COROUTINE)
    }

    override fun onCleared() {
        stopOrigin.compareAndSet(null, VoiceSpeechStopOrigin.LIFECYCLE)
        cancelled.set(true)
        probes.get()?.cancel()
        runner.get()?.cancel()
        engineRunner.get()?.cancel()
        playbackRunner.get()?.cancel(stopOrigin.get() ?: VoiceSpeechStopOrigin.COROUTINE)
        super.onCleared()
    }

    companion object {
        private val failedCleanupOwners = mutableListOf<Any>()
        private val benchmarkLease = VoiceSpeechBenchmarkOwnership()
        val audioOwnership = benchmarkLease.state
    }
}
