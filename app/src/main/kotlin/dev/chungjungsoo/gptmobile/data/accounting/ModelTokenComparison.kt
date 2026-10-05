package dev.chungjungsoo.gptmobile.data.accounting

/** One row per exact model ID, independent of role, provider profile or retry. */
data class ModelTokenComparison(
    val model: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimated: Boolean,
    val roles: List<String>,
    val providers: List<String>
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

fun compareModelTokens(invocations: List<ModelInvocation>): List<ModelTokenComparison> =
    // The ledger supplies the live snapshot first; persisted copies are not extra requests.
    invocations.distinctBy { it.id }.sortedBy { it.startedAt }
        .groupBy { request ->
            request.model.trim().takeIf { it.isNotEmpty() }?.let { "model:$it" }
                ?: "unknown:${request.provider}:${request.profileUid ?: request.id}"
        }
        .values.map { requests ->
            ModelTokenComparison(
                model = requests.first().model.trim().ifEmpty { "Unknown Model" },
                requests = requests.size,
                inputTokens = requests.sumOf { it.inputTokens.coerceAtLeast(0).toLong() },
                outputTokens = requests.sumOf { it.outputTokens.coerceAtLeast(0).toLong() },
                estimated = requests.any { it.estimated },
                roles = requests.map { it.kind }.distinct(),
                providers = requests.map { it.provider }.distinct()
            )
        }
