package dev.chungjungsoo.gptmobile.data.github

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Invocation-local optional ranking; no global profile mutation or implicit remote embedding. */
internal class GitHubTaskContextRanking(
    val fallbackReason: String? = null,
    val rank: suspend (String, Map<String, String>, String) -> Map<String, Double>?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GitHubTaskContextRanking>
}
