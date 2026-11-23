package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.melo.kokoro.TimedKokoro
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/** Qualifies the real graph; retry is limited to the current, entirely unplayed phrase. */
internal class QualcommVoicePhraseEngineFactory(
    context: Context,
    private val backend: VoiceSpeechBackend,
    private val report: (String) -> Unit,
    private val calibrateAll: Boolean = false,
    private val voice: KokoroVoice = KokoroVoice.HEART,
    private val speechRate: Float = 1f
) : VoicePhraseEngineFactory {
    init {
        require(speechRate.isFinite() && speechRate in .5f..2f)
    }

    override val selectedBackend: VoiceSpeechBackend get() = backend

    // Recheck visits fourteen production chunks per backend, including changed-shape graph compilation.
    // Its explicit six-minute bound preserves complete measurements; normal model open keeps its separate thirty-second limit.
    override val preparationTimeoutMs: Long get() = if (backend == VoiceSpeechBackend.AUTO && calibrateAll) 360_000 else 30_000
    private val pending = AtomicReference<TimedKokoro?>(null)
    private val generation = AtomicLong()
    private val calibrated = AtomicBoolean(false)
    private val store = runCatching { VoiceBackendCapabilityStore(context) }.getOrNull()
    private val thermal = context.getSystemService(PowerManager::class.java)
    private val libraryDirectory = context.applicationInfo.nativeLibraryDir
    private val profileDirectory = File(context.cacheDir, "voice-provider-profiles")
    private val deviceKey = store?.deviceKey ?: "unavailable"
    override val identity = "kokoro-${backend.name}-${VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL}-${TimedKokoro.RUNTIME_ID}-${KokoroModelPack.MODEL_SHA256}-$deviceKey-${voice.name}-${speechRate.toBits()}-${store?.revision() ?: 0}"

    override fun cancelPending() {
        generation.incrementAndGet()
        runCatching { pending.get()?.cancel() }
    }

    /** A benchmark measures the requested backend without hidden probes or CPU recovery. */
    fun createBenchmarkEngine(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        require(backend in LOCAL_BACKENDS)
        val ticket = generation.get()
        val native = open(directory, lexicon, threads, backend)
        try {
            checkTicket(ticket)
            var observed: VoiceSpeechExecutionEvidence? = null
            return object : VoicePhraseEngine {
                override val executionProvider: String get() = provider(backend)
                override val executionEvidence: VoiceSpeechExecutionEvidence? get() = observed
                override val executionProgress: VoiceSpeechProgress? get() = native.speechProgress()
                override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                    val audio = try {
                        native.generate(text, speaker, speed)
                    } catch (error: Exception) {
                        observed = speechFailureExecutionEvidence(error, backend, deviceKey)
                        throw error
                    }
                    val receipt = native.evidence()
                    val actual = if (backend != VoiceSpeechBackend.CPU && receipt.acceleratorKernels == 0 && receipt.cpuKernels > 0) "cpu" else executionProvider
                    observed = evidence(receipt, actual, null)
                    return VoicePhraseAudio(audio.samples, audio.durations, audio.wordTokens)
                }
                override fun resetCancellation() = native.resetCancellation()
                override fun cancel() = native.cancel()
                override fun close() = native.close()
            }
        } catch (error: Exception) {
            native.close()
            throw error
        } finally {
            pending.compareAndSet(native, null)
        }
    }

    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
        val ticket = generation.get()
        if (backend == VoiceSpeechBackend.AUTO && calibrateAll && calibrated.compareAndSet(false, true)) {
            // Reject a broken workload before opening a provider or writing any hardware receipt.
            val probes = VoiceSpeechWorkload.calibrationFixtures
            for (candidate in VoiceMeasuredBackendPolicy.comparisonOrder(store?.revision() ?: 0)) {
                checkTicket(ticket)
                var trial: TimedKokoro? = null
                try {
                    notify("Comparing ${candidate.label} with changing phrase shapes, ${voice.label}, at $speechRate×…")
                    val openedThermalStatus = thermalStatus()
                    val started = System.nanoTime()
                    trial = open(directory, lexicon, threads, candidate)
                    measure(trial, candidate, lexicon, threads, ticket, started, probes, openedThermalStatus)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (inputOrSetupFailure(error)) throw error
                    reject(candidate, lexicon, threads, classify(error), error)
                } catch (_: LinkageError) {
                    reject(candidate, lexicon, threads, VoiceSpeechFailureCode.PROVIDER_LOAD)
                } finally {
                    pending.compareAndSet(trial, null)
                    runCatching { trial?.close() }
                }
            }
        }
        checkTicket(ticket)
        val now = System.currentTimeMillis()
        val chosen = if (backend == VoiceSpeechBackend.AUTO) {
            VoiceMeasuredBackendPolicy.choose(LOCAL_BACKENDS.mapNotNull { receipt(it, lexicon, threads, now) }, now)
        } else {
            backend
        }
        val cached = receipt(chosen, lexicon, threads, now)
        var selected = chosen
        var failure = cached?.failure
        var failed = cached?.takeIf { it.failure != null && it.backend in ACCELERATORS }
        if (failed != null) {
            selected = VoiceSpeechBackend.CPU
            notify("${chosen.label}: ${checkNotNull(failure).name.lowercase().replace('_', ' ')}. Using CPU; Recheck will try again.")
        }
        var engine: TimedKokoro? = null
        try {
            engine = if (selected == VoiceSpeechBackend.CPU) openCpu(directory, lexicon, threads, failed) else open(directory, lexicon, threads, selected)
            checkTicket(ticket)
            // The first requested phrase must produce real kernels and aligned audio natively.
            // Normal startup never runs a hidden complete benchmark or manufactures ranking samples.
            if (selected != VoiceSpeechBackend.CPU) notify("${selected.label} will validate the first spoken phrase on this phone.")
        } catch (cancelled: CancellationException) {
            runCatching { engine?.close() }
            throw cancelled
        } catch (error: Exception) {
            runCatching { engine?.close() }
            checkTicket(ticket)
            if (selected == VoiceSpeechBackend.CPU || inputOrSetupFailure(error)) throw error
            failure = classify(error)
            failed = reject(selected, lexicon, threads, failure, error)
            selected = VoiceSpeechBackend.CPU
            engine = openCpu(directory, lexicon, threads, failed, error)
        } catch (error: LinkageError) {
            runCatching { engine?.close() }
            checkTicket(ticket)
            if (selected == VoiceSpeechBackend.CPU) throw error
            failure = VoiceSpeechFailureCode.PROVIDER_LOAD
            failed = reject(selected, lexicon, threads, failure)
            selected = VoiceSpeechBackend.CPU
            engine = openCpu(directory, lexicon, threads, failed, error)
        }
        val ready = checkNotNull(engine)
        try {
            checkTicket(ticket)
            if (backend == VoiceSpeechBackend.AUTO) notify("Auto selected ${selected.label.lowercase()}. Recheck compares all local engines with CPU.")
            return managed(ready, selected, failed, directory, lexicon, threads)
        } catch (error: Exception) {
            ready.close()
            throw error
        } finally {
            pending.compareAndSet(ready, null)
        }
    }

    private fun managed(
        initial: TimedKokoro,
        initialBackend: VoiceSpeechBackend,
        initialFailure: VoiceBackendCapability?,
        directory: File,
        lexicon: String,
        threads: Int
    ): VoicePhraseEngine {
        var failure = initialFailure
        var observed: VoiceSpeechExecutionEvidence? = initialFailure?.let(::failureEvidence)
        var supportRecorded = receipt(initialBackend, lexicon, threads, System.currentTimeMillis())?.let {
            it.failure == null && it.verifiedAccelerator
        } == true
        fun wrap(native: TimedKokoro, selected: VoiceSpeechBackend): VoicePhraseEngine = object : VoicePhraseEngine {
            override val executionProvider: String get() = provider(selected)
            override val executionEvidence: VoiceSpeechExecutionEvidence? get() = observed
            override val executionProgress: VoiceSpeechProgress? get() = native.speechProgress()
            override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                val audio = try {
                    native.generate(text, speaker, speed)
                } catch (error: Exception) {
                    observed = speechFailureExecutionEvidence(error, backend, deviceKey)
                    throw error
                }
                val result = native.evidence()
                observed = evidence(result, executionProvider, failure)
                if (selected != VoiceSpeechBackend.CPU && !supportRecorded && speaker == voice.speakerId && speed == speechRate) {
                    check(result.validatedAudio && result.nonSilentSamples > 0) { "OUTPUT_INVALID" }
                    check(result.profileAvailable) { "EXECUTION_EVIDENCE_MISSING" }
                    check(result.acceleratorKernels > 0) { "CPU_ONLY" }
                    store?.let { cache ->
                        cache.save(
                            VoiceBackendCapability(
                                key(selected, lexicon, threads),
                                selected,
                                System.currentTimeMillis(),
                                verifiedAccelerator = true,
                                comparisonScope = cache.comparisonScope(lexicon, threads, voice, speechRate),
                                speakerId = voice.speakerId,
                                speechRate = speechRate
                            )
                        )
                    }
                    supportRecorded = true
                }
                val actual = if (selected != VoiceSpeechBackend.CPU && result.cpuKernels > 0) "${selected.label} + CPU" else selected.label
                val reason = failure?.failure?.let { " · ${it.name.lowercase().replace('_', ' ')}" }.orEmpty()
                notify("Selected ${backend.label}. Speaking with $actual$reason.")
                return VoicePhraseAudio(audio.samples, audio.durations, audio.wordTokens)
            }
            override fun resetCancellation() = native.resetCancellation()
            override fun cancel() = native.cancel()
            override fun close() = native.close()
        }
        return VoiceFailoverPhraseEngine(wrap(initial, initialBackend), initialBackend != VoiceSpeechBackend.CPU, canRecover = { !inputOrSetupFailure(it) }) { error ->
            failure = reject(initialBackend, lexicon, threads, classify(error), error)
            observed = failureEvidence(checkNotNull(failure))
            val cpu = VoiceNativeEngineGate.exclusive { openCpu(directory, lexicon, threads, failure, error) }
            pending.compareAndSet(cpu, null)
            wrap(cpu, VoiceSpeechBackend.CPU)
        }
    }

    private fun openCpu(directory: File, lexicon: String, threads: Int, failure: VoiceBackendCapability?, original: Throwable? = null): TimedKokoro =
        preserveVoicePreparationFailure(failure?.let(::failureEvidence), original) {
            open(directory, lexicon, threads, VoiceSpeechBackend.CPU)
        }

    private fun open(directory: File, lexicon: String, threads: Int, selected: VoiceSpeechBackend): TimedKokoro {
        if (selected != VoiceSpeechBackend.CPU) {
            check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "DEVICE_UNAVAILABLE" }
            check(
                listOf(
                    "libkokoro_qnn_adapter.so",
                    "libonnxruntime_providers_qnn.so",
                    if (selected == VoiceSpeechBackend.QNN_GPU) "libQnnGpu.so" else "libQnnHtp.so"
                )
                    .all { File(libraryDirectory, it).isFile }
            ) { "PROVIDER_LOAD" }
            check(profileDirectory.isDirectory || profileDirectory.mkdirs()) { "PROVIDER_LOAD" }
        }
        return TimedKokoro(
            File(directory, KokoroModelPack.MODEL).absolutePath,
            File(directory, KokoroModelPack.VOICES).absolutePath,
            File(directory, KokoroModelPack.TOKENS).absolutePath,
            File(directory, lexicon).absolutePath,
            threads,
            provider(selected),
            libraryDirectory,
            File(profileDirectory, UUID.randomUUID().toString()).absolutePath
        ).also { pending.set(it) }
    }

    private fun measure(native: TimedKokoro, selected: VoiceSpeechBackend, lexicon: String, threads: Int, ticket: Long, openedAt: Long, probes: List<List<String>>, openedThermalStatus: Int?) {
        val measurement = measureVoiceQualification(
            probes,
            selected,
            voice.speakerId,
            speechRate,
            openedAt,
            System::nanoTime,
            { checkTicket(ticket) },
            openedThermalStatus,
            ::thermalStatus
        ) { phrase, speaker, rate ->
            native.generate(phrase, speaker, rate)
            evidence(native.evidence(), provider(selected), null)
        }
        store?.save(
            VoiceBackendCapability(
                key(selected, lexicon, threads),
                selected,
                System.currentTimeMillis(),
                measurement.sampleMs.takeIf { measurement.rankingEligible }.orEmpty(),
                selected != VoiceSpeechBackend.CPU,
                comparisonScope = store.comparisonScope(lexicon, threads, voice, speechRate),
                speakerId = voice.speakerId,
                speechRate = speechRate,
                thermalStatuses = measurement.thermalStatuses
            )
        )
        if (!measurement.rankingEligible) notify("${selected.label} execution was validated. A performance ranking needs known, cool thermal readings throughout the comparison.")
    }

    private fun reject(selected: VoiceSpeechBackend, lexicon: String, threads: Int, reason: VoiceSpeechFailureCode, error: Exception? = null): VoiceBackendCapability {
        val native = error as? TimedKokoro.Failure
        val record = VoiceBackendCapability(
            key(selected, lexicon, threads),
            selected,
            System.currentTimeMillis(),
            failure = reason,
            failureStage = native?.stage?.uppercase()?.let { stage -> VoiceSpeechStage.entries.firstOrNull { it.name == stage } }
                ?: VoiceSpeechStage.PREPARING,
            nativeCode = native?.nativeCode,
            tokenCount = native?.tokenCount,
            comparisonScope = store?.comparisonScope(lexicon, threads, voice, speechRate),
            speakerId = voice.speakerId,
            speechRate = speechRate,
            inputBytes = native?.inputBytes ?: (error as? VoiceSpeechWorkloadFailure)?.inputBytes,
            inputByteLimit = native?.inputByteLimit ?: (error as? VoiceSpeechWorkloadFailure)?.inputByteLimit,
            tokenLimit = native?.tokenLimit,
            discoverySummary = safeSpeechDiscoverySummary(native?.discoverySummary)
        )
        store?.save(record)
        notify("${selected.label}: ${reason.name.lowercase().replace('_', ' ')}. Speech can continue on CPU.")
        return record
    }
    private fun receipt(selected: VoiceSpeechBackend, lexicon: String, threads: Int, now: Long) = store?.read(key(selected, lexicon, threads), now)
    private fun key(selected: VoiceSpeechBackend, lexicon: String, threads: Int) = store?.key(selected, lexicon, threads, voice, speechRate).orEmpty()
    private fun checkTicket(ticket: Long) {
        if (generation.get() != ticket) throw CancellationException("Speech cancelled")
    }
    private fun notify(message: String) {
        runCatching { report(message) }
    }
    private fun thermalStatus(): Int? = runCatching { thermal?.currentThermalStatus }.getOrNull()?.takeIf { it in 0..6 }

    private fun evidence(native: TimedKokoro.Evidence, effective: String, failure: VoiceBackendCapability?) = VoiceSpeechExecutionEvidence(
        backend, effective, KokoroModelPack.MODEL_SHA256, TimedKokoro.RUNTIME_ID, deviceKey,
        if (failure == null) VoiceSpeechStage.READY else VoiceSpeechStage.CPU_FALLBACK, failure?.failure,
        native.acceleratorKernels, native.cpuKernels, native.acceleratorMicros, native.cpuMicros, native.prepareMs,
        native.synthesisMs, native.tokenCount, native.validatedAudio, System.currentTimeMillis(), failure?.nativeCode,
        native.deviceType, native.vendorId, native.deviceId,
        failure?.failureStage, failure?.let { provider(it.backend) }, failure?.tokenCount,
        profileAvailable = native.profileAvailable,
        profileId = native.profileId,
        cacheHit = native.cacheHit,
        currentPrepareMs = native.currentPrepareMs,
        cachedShapeCount = native.cachedShapeCount,
        cacheLimit = native.cacheLimit,
        cacheEstimatedBytes = native.cacheEstimatedBytes,
        audioRms = native.audioRms,
        audioPeak = native.audioPeak,
        clippedSamples = native.clippedSamples,
        nonSilentSamples = native.nonSilentSamples,
        profileReused = native.profileReused,
        graphPreparedThisCall = native.graphPreparedThisCall,
        discoverySummary = safeSpeechDiscoverySummary(native.discoverySummary),
        failedInputBytes = failure?.inputBytes,
        failedInputByteLimit = failure?.inputByteLimit,
        failedTokenLimit = failure?.tokenLimit,
        failedDiscoverySummary = safeSpeechDiscoverySummary(failure?.discoverySummary),
        frontendMs = native.frontendMs,
        validationMs = native.validationMs,
        cacheEvictions = native.cacheEvictions,
        profileFinalizeMs = native.profileFinalizeMs
    )

    private fun failureEvidence(failure: VoiceBackendCapability) = VoiceSpeechExecutionEvidence(
        backend, "cpu", KokoroModelPack.MODEL_SHA256, TimedKokoro.RUNTIME_ID, deviceKey,
        VoiceSpeechStage.CPU_FALLBACK, failure.failure, tokenCount = failure.tokenCount ?: 0,
        observedAtMillis = System.currentTimeMillis(), nativeCode = failure.nativeCode,
        failureStage = failure.failureStage, failedProvider = provider(failure.backend), failedTokenCount = failure.tokenCount,
        failedInputBytes = failure.inputBytes, failedInputByteLimit = failure.inputByteLimit,
        failedTokenLimit = failure.tokenLimit, failedDiscoverySummary = safeSpeechDiscoverySummary(failure.discoverySummary)
    )

    companion object {
        private val ACCELERATORS = setOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)
        private val LOCAL_BACKENDS = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)
        private fun provider(value: VoiceSpeechBackend) = when (value) {
            VoiceSpeechBackend.CPU -> "cpu"
            VoiceSpeechBackend.QNN_GPU -> "qnn-gpu"
            VoiceSpeechBackend.QNN_HTP -> "qnn-htp"
            else -> error("Local speech backend required")
        }
        private fun classify(error: Exception): VoiceSpeechFailureCode {
            val code = (error as? TimedKokoro.Failure)?.code ?: error.message
            return VoiceSpeechFailureCode.entries.firstOrNull { it.name == code } ?: VoiceSpeechFailureCode.EXECUTION_FAILED
        }
        private fun inputOrSetupFailure(error: Exception): Boolean =
            (error as? TimedKokoro.Failure)?.stage.equals("frontend", ignoreCase = true) || classify(error).let { it.isInputFailure || it.isSetupFailure }
        fun requestRetest(context: Context, backend: VoiceSpeechBackend) {
            if (backend == VoiceSpeechBackend.AUTO || backend in LOCAL_BACKENDS) {
                runCatching { VoiceBackendCapabilityStore(context).retest(backend) }
            }
        }
    }
}

internal class UnavailableVoicePhraseEngineFactory(private val reason: String) : VoicePhraseEngineFactory {
    override val identity = "voice-unavailable-$reason"
    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error(reason)
}
