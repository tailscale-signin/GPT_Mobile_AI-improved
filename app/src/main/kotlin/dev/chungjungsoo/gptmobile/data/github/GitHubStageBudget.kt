package dev.chungjungsoo.gptmobile.data.github

/** Evidence review, correction and claim rendering reserve their own bounded output. */
internal object GitHubStageBudget {
    data class Allowance(val input: Int, val output: Int)

    fun resolve(context: Int, outputCap: Int?, role: String): Allowance {
        val preferred = when {
            role.endsWith("-preflight") -> 512
            role.endsWith("-repair") || role == "github-claim-extraction" -> 4096
            else -> GitHubReviewSchema.OUTPUT_TOKENS
        }
        val capacity = context.coerceIn(0, 1_048_576)
        val output = minOf(preferred, capacity / 4, outputCap?.coerceAtLeast(0) ?: Int.MAX_VALUE)
        return Allowance((capacity - output - 1536).coerceAtLeast(0), output)
    }
}
