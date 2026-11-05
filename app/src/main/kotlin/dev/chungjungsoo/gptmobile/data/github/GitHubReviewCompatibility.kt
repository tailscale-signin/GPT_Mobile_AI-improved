package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache

/** A short synthetic check uses only the selected route. No user prompt or GitHub write is sent. */
internal class GitHubReviewCompatibility(now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val cache = RouteCapabilityCache<Boolean>(now, positiveTtl = 300_000, negativeTtl = 30_000)

    suspend fun supports(revision: String, probe: suspend () -> Boolean): Boolean =
        cache.getOrLoad("${GitHubReviewSchema.VERSION}:$revision") { true.takeIf { probe() } } == true

    companion object {
        const val EVIDENCE_ID = "probe:0"
        const val PROMPT = """{"phase":"review","candidate_scope":"complete","task":"Check this synthetic status only.","candidate_answer":"The synthetic check passed.","records":[{"id":"probe:0","evidence":"{\"status\":\"passed\"}"}],"verified_claims":[],"review_issues":[]}"""
    }
}
