package dev.chungjungsoo.gptmobile.data.agent

/** A planning allowance, still bounded by each accepted profile/provider and turn hard limit. */
internal object CombinedContributorAllowance {
    fun preferred(requested: CombinedRequestedPolicy, caps: List<CombinedCapRecord>): Int {
        val effort = requested.settings.normalized().effort / 25
        val base = listOf(512, 1024, 2048, 4096, 8192)[effort]
        val explicitWords = Regex("(?i)\\b(?:exactly\\s+|about\\s+|approximately\\s+)?(\\d{1,7})[ -]+words?\\b")
            .find(requested.userPrompt)?.groupValues?.get(1)?.toLongOrNull()
        val scope = explicitWords?.let { (it * 2 + 128).coerceAtMost(8192).toInt() } ?: 0
        val expanded = CombinedSynthesisPreferences.from(requested.settings).verbosity >= 75
        val visible = maxOf(base, scope, if (expanded || requested.acceptedResearch != null) 4096 else 0)
        val reasoningExpected = caps.any { it.name == "profileReasoningExpected" && it.profileUid in requested.participantUids && it.value == 1L }
        val reasoning = if (reasoningExpected) maxOf(2048, visible / 2).coerceAtMost(4096) else 0
        val fixed = caps.filter { it.name == "providerFixedOutputTokens" && it.origin == CombinedCapOrigin.PROVIDER && it.profileUid in requested.participantUids }
            .maxOfOrNull { it.value.coerceIn(1, Int.MAX_VALUE.toLong()).toInt() } ?: 0
        return maxOf(visible + reasoning, fixed)
    }

    fun boundedForProfile(preferred: Int, uid: String, caps: List<CombinedCapRecord>): Int =
        caps.filter { it.name == "profileOutputTokens" && it.profileUid == uid && it.value > 0 }
            .minOfOrNull { minOf(preferred.toLong(), it.value).toInt() } ?: preferred
}
