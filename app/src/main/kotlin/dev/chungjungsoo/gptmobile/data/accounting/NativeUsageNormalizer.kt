package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics

/** One accumulator per physical provider request, with separate native generation segments. */
internal class NativeUsageNormalizer {
    private val segments = linkedMapOf<String, LocalInferenceMetrics>()

    fun accept(metrics: LocalInferenceMetrics, fallbackSegmentId: String, runtime: String?): ProviderEvent.Usage {
        val id = metrics.segmentId?.takeIf { it.isNotBlank() } ?: fallbackSegmentId
        val prior = segments[id]
        // Exact counters replace estimates. Duplicated cumulative receipts replace, never add.
        val native = metrics.native?.takeIf { it.hasValidTokenCounts }
        val previousNative = prior?.native?.takeIf { it.hasValidTokenCounts }
        segments[id] = when {
            previousNative != null && native == null -> requireNotNull(prior)
            previousNative != null && native != null -> metrics.copy(native = native.copy(prefillTokens = maxOf(previousNative.prefillTokens, native.prefillTokens), decodeTokens = maxOf(previousNative.decodeTokens, native.decodeTokens)))
            else -> metrics
        }
        val allNative = segments.values.all { it.native?.hasValidTokenCounts == true }
        val input = if (allNative) count { it.native!!.prefillTokens } else null
        val output = count { it.native?.takeIf { n -> n.hasValidTokenCounts }?.decodeTokens ?: it.estimatedTokens.coerceAtLeast(0) }
        return ProviderEvent.Usage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = input?.let { (it.toLong() + output).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
            cumulative = true,
            decodeTokensPerSecond = metrics.native?.decodeTokensPerSecond?.takeIf { it.isFinite() && it > 0 },
            finishReason = metrics.finishReason,
            inputSource = if (allNative) "runtime_reported" else "unavailable",
            outputSource = if (allNative) "runtime_reported" else "estimated",
            actualRuntime = runtime
        )
    }

    private fun count(value: (LocalInferenceMetrics) -> Int): Int = segments.values.sumOf { value(it).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
