package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RequestAdmission

/** Evidence review, correction and claim rendering reserve their own bounded output. */
internal object GitHubStageBudget {
    data class Allowance(val input: Int, val output: Int)

    fun resolve(context: Int, outputCap: Int?, role: String): Allowance {
        val preferred = when {
            role.endsWith("-preflight") -> GitHubReviewCompatibility.OUTPUT_TOKENS
            role.endsWith("-output-retry") -> 4096
            role.endsWith("-repair") || role == "github-claim-extraction" -> 4096
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
        return Allowance(lower.toInt(), output)
    }
}
