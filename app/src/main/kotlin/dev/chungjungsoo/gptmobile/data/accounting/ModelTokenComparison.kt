package dev.chungjungsoo.gptmobile.data.accounting

/** A display aggregate only: never replaces invocation records or budget reservations. */
internal data class ModelTokenComparison(
    val provider: String,
    val model: String,
    val requestCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimated: Boolean,
    val kinds: List<String>
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * Primary, delegate, reviewer and retry requests for the same exact model share a row.
 * Do not strip provider prefixes, quantization suffixes, version tags or letter case.
 * The caller supplies current observations first, so duplicate request IDs count once.
 */
internal fun compareModelTokens(invocations: List<ModelInvocation>): List<ModelTokenComparison> =
    invocations.distinctBy { it.id }
        .sortedBy { it.startedAt }
        .groupBy { request ->
            Triple(
                request.provider.trim(),
                request.model.trim(),
                request.id.takeIf { request.provider.isBlank() || request.model.isBlank() }
            )
        }
        .values.map { requests ->
            ModelTokenComparison(
                provider = requests.first().provider.trim(),
                model = requests.first().model.trim(),
                requestCount = requests.size,
                inputTokens = requests.sumOf { it.inputTokens.toLong().coerceAtLeast(0L) },
                outputTokens = requests.sumOf { it.outputTokens.toLong().coerceAtLeast(0L) },
                estimated = requests.any { it.estimated },
                kinds = requests.map { it.kind }.distinct().sorted()
            )
        }
