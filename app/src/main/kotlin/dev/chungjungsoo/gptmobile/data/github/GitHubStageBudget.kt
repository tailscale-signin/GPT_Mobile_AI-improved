package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import kotlinx.serialization.json.JsonPrimitive

/** Evidence review, correction and claim rendering reserve their own bounded output. */
internal object GitHubStageBudget {
    const val CONTINUATION_ROLE = "github-output-continuation"
    const val VISIBLE_OUTPUT_REPAIR_ROLE = "github-visible-output-continuation"

    /** Target for effort-only APIs; manual-thinking APIs apply it above their minimum budget. */
    data class Allowance(val input: Int, val output: Int, val visibleOutput: Int = 0)

    fun visibleOutputReserve(output: Int, role: String?): Int = when (role) {
        CONTINUATION_ROLE -> minOf(2048, output.coerceAtLeast(0) / 2)
        VISIBLE_OUTPUT_REPAIR_ROLE -> minOf(4096, output.coerceAtLeast(0) / 2)
        else -> 0
    }

    /** Input and output rejection ceilings cannot describe the complete request context. */
    fun observedContext(admittedContext: Long?, failure: ProviderEvent.Failed?): Int? {
        val rejection = failure?.takeIf { it.errorCode == "CONTEXT_CAPACITY_EXCEEDED" }?.failureDetails
        val rejectedContext = if (rejection?.get("admission_capacity_provenance") in setOf(JsonPrimitive("VERIFIED_ROUTE"), JsonPrimitive("DECLARED"))) {
            (rejection?.get("admission_capacity_tokens") as? JsonPrimitive)?.content?.toLongOrNull()
        } else {
            null
        }
        return (admittedContext ?: rejectedContext)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    fun resolve(context: Int, outputCap: Int?, role: String): Allowance {
        val preferred = when {
            role == GitHubReviewCompatibility.OUTPUT_RETRY_ROLE -> GitHubReviewCompatibility.OUTPUT_RETRY_TOKENS
            role.endsWith("-preflight") -> GitHubReviewCompatibility.OUTPUT_TOKENS
            role.endsWith("-output-retry") -> 4096
            role == "github-claim-extraction" -> 8192
            role == CONTINUATION_ROLE -> 8192
            role == VISIBLE_OUTPUT_REPAIR_ROLE -> 16_384
            role.endsWith("-repair") -> 4096
            else -> GitHubReviewSchema.OUTPUT_TOKENS
        }
        val capacity = context.coerceIn(0, 1_048_576)
        val output = minOf(preferred, capacity / 4, outputCap?.coerceAtLeast(0) ?: Int.MAX_VALUE)
        // The review measurer includes its JSON envelope. Keep room for provider
        // instructions/template fields added by the final serializer as well as
        // the same proportional uncertainty that RequestAdmission will require.
        val available = (capacity - output).coerceAtLeast(0).toLong()
        var lower = 0L
        var upper = (available - 1536).coerceAtLeast(0)
        while (lower < upper) {
            val middle = lower + (upper - lower + 1) / 2
            val serialized = middle + 1536
            if (serialized + RequestAdmission.estimateSafety(serialized) <= available) lower = middle else upper = middle - 1
        }
        return Allowance(lower.toInt(), output, visibleOutputReserve(output, role))
    }
}
