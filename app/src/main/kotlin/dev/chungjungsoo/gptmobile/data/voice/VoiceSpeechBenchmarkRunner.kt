package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** CPU/GPU/NPU receive identical text, voice, speed and thread settings, on a single worker. */
internal class VoiceSpeechBenchmarkRunner(
    private val create: (VoiceSpeechBackend) -> VoicePhraseEngine,
    private val cancelPreparation: () -> Unit = {},
    private val thermalStatus: () -> Int? = { null },
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Long = System::currentTimeMillis
) {
    private val cancelled = AtomicBoolean(false)
    private val currentEngine = AtomicReference<VoicePhraseEngine?>(null)

    fun cancel() {
        cancelled.set(true)
        runCatching { cancelPreparation() }
        runCatching { currentEngine.get()?.cancel() }
    }

    fun run(initial: VoiceSpeechBenchmarkReport, publish: (VoiceSpeechBenchmarkReport) -> Unit): VoiceSpeechBenchmarkReport {
        var report = initial
        fun emit() = publish(report)
        fun update(index: Int, result: VoiceSpeechBenchmarkBackendResult) {
            report = report.copy(results = report.results.toMutableList().also { it[index] = result })
            emit()
        }
        emit()
        for (index in report.results.indices) {
            if (cancelled.get()) break
            if ((thermalStatus() ?: 0) >= 2) {
                report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                break
            }
            var result = report.results[index].copy(state = VoiceSpeechBenchmarkBackendState.PREPARING)
            update(index, result)
            var engine: VoicePhraseEngine? = null
            try {
                checkActive()
                var preparedAt = 0L
                var preparationMs = 0L
                engine = VoiceNativeEngineGate.exclusive {
                    checkActive()
                    preparedAt = nanoTime()
                    create(result.requestedBackend).also {
                        preparationMs = elapsed(preparedAt)
                        currentEngine.set(it)
                    }
                }
                checkActive()
                result = result.copy(sessionPrepareMs = preparationMs, state = VoiceSpeechBenchmarkBackendState.RUNNING)
                update(index, result)
                for (fixture in VoiceSpeechBenchmarkFixture.entries) {
                    for (iteration in 0..report.configuration.mode.warmRepeats) {
                        checkActive()
                        val before = thermalStatus()
                        if ((before ?: 0) >= 2) {
                            report = report.copy(issue = VoiceSpeechBenchmarkIssue.THERMAL_LIMIT)
                            throw CancellationException("Thermal limit")
                        }
                        report = report.copy(activeFixture = fixture, activeRepeat = iteration)
                        emit()
                        val ready = checkNotNull(engine)
                        val sample = VoiceNativeEngineGate.exclusive {
                            checkActive()
                            ready.resetCancellation()
                            checkActive()
                            val started = nanoTime()
                            val audio = ready.generate(fixture.text, report.configuration.voice.speakerId, report.configuration.speechRate)
                            val firstPcmMs = elapsed(started)
                            checkActive()
                            val evidence = ready.executionEvidence
                            check(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() }) { "OUTPUT_INVALID" }
                            try {
                                kokoroWordFrames(audio.durations, audio.wordTokens, audio.wordTokens.size / 2, audio.samples.size)
                            } catch (_: IllegalArgumentException) {
                                error("OUTPUT_INVALID")
                            }
                            check(evidence?.verifiedAudio == true) { "OUTPUT_INVALID" }
                            val observed = checkNotNull(evidence)
                            check(observed.effectiveProvider == VoiceSpeechBenchmarkBackendResult.provider(result.requestedBackend)) { "CPU_ONLY" }
                            if (result.requestedBackend != VoiceSpeechBackend.CPU) check(observed.acceleratorKernels > 0) { "CPU_ONLY" }
                            VoiceSpeechBenchmarkSample(
                                fixture = fixture,
                                repeat = iteration,
                                generationMs = firstPcmMs,
                                firstPcmMs = firstPcmMs,
                                prepareToFirstPcmMs = elapsed(preparedAt).takeIf { result.samples.isEmpty() },
                                audioMs = audio.samples.size * 1000.0 / SAMPLE_RATE,
                                effectiveProvider = observed.effectiveProvider,
                                acceleratorKernels = observed.acceleratorKernels,
                                cpuKernels = observed.cpuKernels,
                                acceleratorMicros = observed.acceleratorMicros,
                                cpuMicros = observed.cpuMicros,
                                graphPrepareMs = observed.prepareMs,
                                tokenCount = observed.tokenCount,
                                validatedAudio = observed.verifiedAudio,
                                thermalBefore = before,
                                thermalAfter = thermalStatus(),
                                hardwareType = observed.hardwareType
                            )
                        }
                        result = result.copy(samples = result.samples + sample)
                        update(index, result)
                    }
                }
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.COMPLETE)
            } catch (_: CancellationException) {
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.CANCELLED)
            } catch (error: Exception) {
                val failure = speechBenchmarkFailure(error)
                val observed = engine?.executionEvidence
                result = result.copy(
                    state = failure.state,
                    failure = failure.code,
                    failureStage = failure.stage,
                    nativeCode = failure.nativeCode,
                    failureEffectiveProvider = observed?.effectiveProvider,
                    failureAcceleratorKernels = observed?.acceleratorKernels,
                    failureCpuKernels = observed?.cpuKernels
                )
            } catch (_: LinkageError) {
                result = result.copy(state = VoiceSpeechBenchmarkBackendState.UNSUPPORTED, failure = VoiceSpeechFailureCode.PROVIDER_LOAD)
            } finally {
                currentEngine.compareAndSet(engine, null)
                VoiceNativeEngineGate.exclusive { runCatching { engine?.close() } }
                update(index, result)
            }
            if (result.state == VoiceSpeechBenchmarkBackendState.CANCELLED) break
        }
        val finalState = when {
            cancelled.get() -> VoiceSpeechBenchmarkRunState.CANCELLED
            report.issue != null -> VoiceSpeechBenchmarkRunState.INTERRUPTED
            else -> VoiceSpeechBenchmarkRunState.COMPLETE
        }
        report = report.copy(
            state = finalState,
            finishedAtMillis = wallTime(),
            issue = if (cancelled.get()) VoiceSpeechBenchmarkIssue.CANCELLED else report.issue,
            activeFixture = null,
            activeRepeat = null,
            results = report.results.map { if (it.state == VoiceSpeechBenchmarkBackendState.PENDING) it.copy(state = VoiceSpeechBenchmarkBackendState.NOT_RUN) else it }
        )
        emit()
        return report
    }

    private fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Speech benchmark cancelled")
    }
    private fun elapsed(started: Long): Long = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000).coerceAtLeast(1)

    companion object {
        const val SAMPLE_RATE = 24_000
    }
}

internal data class VoiceSpeechBenchmarkFailure(
    val code: VoiceSpeechFailureCode,
    val state: VoiceSpeechBenchmarkBackendState,
    val stage: VoiceSpeechStage? = null,
    val nativeCode: Int? = null
)

internal fun speechBenchmarkFailure(error: Exception): VoiceSpeechBenchmarkFailure {
    val native = error as? dev.melo.kokoro.TimedKokoro.Failure
    val code = VoiceSpeechFailureCode.entries.firstOrNull { it.name == (native?.code ?: error.message) } ?: VoiceSpeechFailureCode.EXECUTION_FAILED
    return VoiceSpeechBenchmarkFailure(
        code,
        if (code in setOf(VoiceSpeechFailureCode.DEVICE_UNAVAILABLE, VoiceSpeechFailureCode.PROVIDER_LOAD, VoiceSpeechFailureCode.CPU_ONLY)) {
            VoiceSpeechBenchmarkBackendState.UNSUPPORTED
        } else {
            VoiceSpeechBenchmarkBackendState.FAILED
        },
        native?.stage?.uppercase()?.let { stage -> VoiceSpeechStage.entries.firstOrNull { it.name == stage } },
        native?.nativeCode
    )
}
