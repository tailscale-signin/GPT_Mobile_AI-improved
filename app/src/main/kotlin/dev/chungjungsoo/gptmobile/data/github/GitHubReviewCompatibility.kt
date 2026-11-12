package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache

/** A short synthetic check uses only the selected route. No user prompt or GitHub write is sent. */
internal class GitHubReviewCompatibility(now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val cache = RouteCapabilityCache<Boolean>(now, positiveTtl = 300_000, negativeTtl = 30_000)

    sealed interface Result {
        data object Supported : Result
        data object UnsupportedFormat : Result
        data class Unavailable(val failure: ProviderEvent.Failed) : Result
    }

    private class Unavailable(val failure: ProviderEvent.Failed) : Exception()

    /** Only a complete, observed format verdict may populate the negative cache. */
    suspend fun check(revision: String, probe: suspend () -> Result): Result = try {
        val supported = cache.getOrLoad("${GitHubReviewSchema.VERSION}:$revision") {
            when (val result = probe()) {
                Result.Supported -> true
                Result.UnsupportedFormat -> null
                is Result.Unavailable -> throw Unavailable(result.failure)
            }
        }
        if (supported == true) Result.Supported else Result.UnsupportedFormat
    } catch (unavailable: Unavailable) {
        Result.Unavailable(unavailable.failure)
    }

    suspend fun supports(revision: String, probe: suspend () -> Boolean): Boolean =
        check(revision) { if (probe()) Result.Supported else Result.UnsupportedFormat } == Result.Supported

    companion object {
        // The selected profile may explicitly require reasoning. Leave room for
        // that policy and a visible verdict instead of imposing a 512-token cap.
        const val OUTPUT_TOKENS = 2048
        const val EVIDENCE_ID = "probe:0"
        const val PROMPT = """{"phase":"review","candidate_scope":"complete","task":"Check this synthetic status only.","candidate_answer":"The synthetic check passed.","records":[{"id":"probe:0","evidence":"{\"status\":\"passed\"}"}],"verified_claims":[],"review_issues":[]}"""
        const val BOUNDED_RETRY_PROMPT = """{"phase":"review","candidate_scope":"complete","task":"Check only whether the candidate matches the one supplied synthetic status. Return one compact verdict, one short reason, no issues if supported, and at most one verified claim. Do not elaborate.","candidate_answer":"The synthetic check passed.","records":[{"id":"probe:0","evidence":"{\"status\":\"passed\"}"}],"verified_claims":[],"review_issues":[]}"""
    }
}
