package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalConversationConfig
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeEvent
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.LocalWorkloadAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.isLocalMemoryAdmissionFailure
import dev.chungjungsoo.gptmobile.data.localruntime.QualificationStatus
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeEvidenceIdentity
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationResult
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeTrial
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class LocalComparisonProgress(val label: String, val completed: Int, val total: Int, val deadlineMs: Long)
data class LocalBenchmarkCandidate(val label: String, val spec: LocalEngineSpec)

/** Uses the existing foreground benchmark owner and admission lease; never changes saved tuning. */
@Singleton
class LocalRuntimeBenchmarkSuite @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runtime: LocalRuntime,
    private val store: BenchmarkStore,
    private val coordinator: BenchmarkExecutionCoordinator
) {
    private val mutableProgress = MutableStateFlow<LocalComparisonProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun start(candidates: List<LocalBenchmarkCandidate>, sustained: Boolean = false): Boolean {
        require(candidates.isNotEmpty() && candidates.size <= 8)
        return coordinator.launch {
            mutableError.value = null
            try {
                InferenceAdmission.benchmark {
                    runtime.runExclusive {
                        val duration = if (sustained) (10 + 50 * candidates.size) * 60_000L else 6 * 60_000L
                        val deadline = SystemClock.elapsedRealtime() + duration
                        withTimeout(duration) {
                            var count = 0
                            val total = candidates.size * 12
                            // Rotate candidate order to limit systematic warm-up and heat bias.
                            repeat(6) { repetition ->
                                for (offset in candidates.indices) {
                                    val candidate = candidates[(offset + repetition) % candidates.size]
                                    for (band in listOf(256, 1024)) {
                                        mutableProgress.value = LocalComparisonProgress(candidate.label, count, total, deadline)
                                        coordinator.progressMutable.value = BenchmarkProgress(candidate.label, "Local runtime comparison", count, total)
                                        runTrial(candidate, band, warmup = repetition == 0, cold = repetition == 0, sustained = false)
                                        count++
                                    }
                                }
                            }
                            if (sustained) {
                                repeat(3) { block ->
                                    for (offset in candidates.indices) {
                                        val candidate = candidates[(offset + block) % candidates.size]
                                        runTrial(candidate, 1024, warmup = true, cold = true, sustained = true)
                                        val end = SystemClock.elapsedRealtime() + 15 * 60_000L
                                        while (SystemClock.elapsedRealtime() < end) {
                                            mutableProgress.value = LocalComparisonProgress("Sustained block ${block + 1}: ${candidate.label}", count++, total, deadline)
                                            coordinator.progressMutable.value = BenchmarkProgress(candidate.label, "Sustained block ${block + 1}", count, total)
                                            runTrial(candidate, 1024, warmup = false, cold = false, sustained = true)
                                        }
                                    }
                                }
                            }
                            store.load()
                            store.runtimeTrials.value.filter { it.finished && !it.historical && !it.warmup }.groupBy { it.key }.forEach { (key, trials) ->
                                val measured = trials.filter { it.completed && it.nativeMetrics?.isValid == true }
                                if (measured.isNotEmpty()) {
                                    store.saveQualification(
                                        RuntimeQualificationResult(
                                            key = key, plan = measured.first().plan, status = QualificationStatus.MEASURED,
                                            sourceRunIds = trials.map { it.id }, completedRuns = trials.count { it.completed }, failedRuns = trials.count { !it.completed },
                                            medianDecodeTokensPerSecond = median(measured.map { it.nativeMetrics!!.decodeTokensPerSecond }),
                                            p95FirstVisibleMs = percentile95(measured.mapNotNull { it.firstVisibleAnswerMs }),
                                            medianLoadMs = median(measured.filter { it.cold }.mapNotNull { it.loadMs?.toDouble() })?.toLong(),
                                            sustainedMedianDecodeTokensPerSecond = median(measured.filter { it.sustained && !it.cold }.map { it.nativeMetrics!!.decodeTokensPerSecond }),
                                            measuredAt = System.currentTimeMillis(), reason = "Measured only; full quality, recovery and backend qualification gates remain required"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableError.value = coordinator.stopReason ?: "Comparison stopped or reached its time limit. Completed trials remain saved."
                throw cancelled
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Local comparison failed"
            } finally {
                mutableProgress.value = null
            }
        } != null
    }

    fun stop() {
        coordinator.cancel("Local comparison stopped by user")
        runtime.cancelActive()
    }

    private suspend fun runTrial(candidate: LocalBenchmarkCandidate, band: Int, warmup: Boolean, cold: Boolean, sustained: Boolean) {
        kotlin.coroutines.coroutineContext.ensureActive()
        val spec = candidate.spec.copy(nativeMetricsEnabled = true, tuningLocked = true, samplerIdentity = if (candidate.spec.accelerator == "npu") "runtime_default" else "1|1.0|0.0", workload = "plain_text")
        val key = RuntimeEvidenceIdentity.key(context, spec, spec.samplerIdentity)
        val plan = RuntimeEvidenceIdentity.plan(key)
        val start = SystemClock.elapsedRealtime()
        val trialId = UUID.randomUUID().toString()
        store.saveRuntimeTrial(RuntimeTrial(trialId, key, plan, System.currentTimeMillis(), completed = false, finished = false, durationMs = 0, cold = cold, sustained = sustained, warmup = warmup, promptTokenBand = band, appCommit = BuildConfig.APP_COMMIT))
        var wasCold = cold
        var loadMs: Long? = null
        var metrics: LocalInferenceMetrics? = null
        var completed = false
        var failure: String? = null
        var characters = 0
        try {
            withTimeout(120_000) {
                while ((!sustained && runtime.getHardwareState().isModeratePressure) || runtime.getHardwareState().isThrottlingRequired) delay(10_000)
            }
            withTimeout(90_000) {
                wasCold = cold || !runtime.isEngineLoaded(spec)
                if (cold) runtime.unloadEngine()
                runtime.closeConversation()
                val loadStart = SystemClock.elapsedRealtime()
                runtime.loadEngine(spec)
                check(runtime.loadedEngineSpec()?.accelerator == spec.accelerator) { "Backend fallback excluded this candidate from the comparison" }
                loadMs = SystemClock.elapsedRealtime() - loadStart
                runtime.createConversation(
                    LocalConversationConfig(
                        sampler = LocalSamplerConfig(1, 1f, 0f),
                        systemPrompt = "Follow the instruction accurately.",
                        initialMessages = emptyList(),
                        maxOutputTokens = minOf(128, (spec.maxTokens / 4).coerceAtLeast(1)),
                        thinkingEnabled = false,
                        speculativeDecoding = spec.speculativeDecoding
                    )
                )
                val prompt = "Summarize these observations in plain language, preserving the numbers: " +
                    (1..(band / 16)).joinToString(" ") { "Observation $it: the test collected three samples during the morning and two during the afternoon." }
                runtime.sendMessage(prompt).collect { event ->
                    when (event) {
                        is LocalRuntimeEvent.Metrics -> metrics = event.metrics
                        is LocalRuntimeEvent.TextDelta -> characters += event.text.length
                        is LocalRuntimeEvent.Error -> failure = event.message
                        LocalRuntimeEvent.Done -> completed = true
                        else -> Unit
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            failure = "Cancelled or timed out"
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "Local inference failed"
            // One rejected native load is sufficient evidence that this configuration
            // cannot benchmark under the current memory budget. Save the failed trial,
            // then stop instead of running eleven identical failed trials.
            if (error.isLocalMemoryAdmissionFailure()) throw error
        } finally {
            val hardware = runtime.getHardwareState()
            withContext(NonCancellable) {
                store.saveRuntimeTrial(
                    RuntimeTrial(
                        id = trialId, key = key, plan = plan, timestamp = System.currentTimeMillis(),
                        completed = completed && failure == null && characters > 0, nativeMetrics = metrics?.native,
                        firstVisibleAnswerMs = metrics?.firstVisibleAnswerMs, loadMs = loadMs, durationMs = SystemClock.elapsedRealtime() - start,
                        failure = failure, thermalState = hardware.thermalState.name, batteryPercent = hardware.batteryPct, charging = hardware.isCharging,
                        availableMemoryBytes = LocalWorkloadAdmission.availableMemory(context), processPssBytes = runtime.processPssBytes, cold = wasCold, sustained = sustained, warmup = warmup,
                        promptTokenBand = band, conversationReused = false, outputCharacters = characters, requestedOutputTokens = minOf(128, (spec.maxTokens / 4).coerceAtLeast(1)), appCommit = BuildConfig.APP_COMMIT
                    )
                )
                runtime.closeConversation()
            }
        }
    }

    companion object {
        internal fun median(values: List<Double>): Double? = values.filter { it.isFinite() }.sorted().let { sorted ->
            if (sorted.isEmpty()) {
                null
            } else if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            } else {
                sorted[sorted.size / 2]
            }
        }
        internal fun percentile95(values: List<Long>): Long? = values.sorted().let { if (it.isEmpty()) null else it[(kotlin.math.ceil(it.size * .95).toInt() - 1).coerceAtLeast(0)] }
    }
}
