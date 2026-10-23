package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Unknown routes may be probed; only validated records count as demonstrated structured delivery. */
internal object CombinedWriterPolicy {
    fun requiredOutput(request: String, profile: PlatformV2): Int {
        val policy = CombinedAnswerPolicy.from(request)
        val words = policy.maximumWords ?: policy.targetWords ?: policy.minimumWords ?: if (CombinedTaskContract.from(request).action == "CONVERSATION") 30 else 500
        val observation = CombinedWriterObservations.get(CombinedWriterObservations.key(profile))
        val reasoningReserve = when {
            (observation?.reasoningTokensHighWater ?: 0) > 0 -> (observation!!.reasoningTokensHighWater.toLong() * 5 / 4 + 256).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            observation?.reasoningDisabled in setOf("NOT_HONORED", "DISABLE_UNVERIFIED") -> 4096
            observation?.reasoningDisabled == "OBSERVED_NO_REASONING" && observation.completeAnswers > 0 -> 0
            else -> 4096
        }
        return maxOf((words * 2.2 + 512 + reasoningReserve).toLong(), observation?.minimumUsefulOutputAllowance?.toLong() ?: 0L, observation?.stalledOutputAllowance?.toLong()?.plus(512) ?: 0L).coerceIn(768, Int.MAX_VALUE.toLong()).toInt()
    }
    fun unavailableReason(profile: PlatformV2, request: String, inputTokens: Int = 0): String? {
        if (CurrentRequestContract.from(request).intent in setOf("GREETING", "TEST", "PROVIDER_TEST")) return null
        val required = requiredOutput(request, profile)
        val cap = ProviderRouteCapacity.output(profile, required)
        if (cap < required) return "Final writing needs at least $required generation tokens including record framing; this route allows $cap. Its contributions remain available."
        val observed = CombinedWriterObservations.get(CombinedWriterObservations.key(profile))
        if ((observed?.reasoningOnlyAttempts ?: 0) >= 2) return "This route exhausted its bounded reasoning recovery without an answer passage. Choose another writer or model; saved contributions remain available."
        if ((observed?.malformedAttempts ?: 0) >= 2) return "This route repeatedly failed structured delivery. Choose another writer; saved passages remain available."
        if ((observed?.queueDelays ?: 0) >= 1) return "This route exceeded the interactive queue wait. Its remote receipt remains available."
        if (inputTokens > 0 && ProviderRouteCapacity.context(profile)?.let { inputTokens.toLong() + required > it } == true) return "The assembled input and final output exceed this model’s known context capacity."
        return null
    }
    fun rank(profiles: List<PlatformV2>, request: String, preferredUid: String?, inputTokens: Int = 0): List<PlatformV2> = profiles
        .filter { CombinedProviderEligibility.unavailableReason(it, request, writer = true) == null && unavailableReason(it, request, inputTokens) == null }
        .sortedWith(
            compareBy<PlatformV2> {
                CombinedWriterObservations.get(CombinedWriterObservations.key(it))?.let { o -> o.reasoningOnlyAttempts * 4 + o.malformedAttempts * 5 + o.emptyAttempts * 3 + o.queueDelays * 5 + o.incompleteAnswers * 3 } ?: 0
            }.thenBy { it.uid != preferredUid }.thenByDescending { CombinedWriterObservations.get(CombinedWriterObservations.key(it))?.completeAnswers ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.uid }
        )
}
