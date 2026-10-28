package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Unknown routes may be probed; only validated records count as demonstrated structured delivery. */
internal object CombinedWriterPolicy {
    /** Fund useful packets on capable routes; the minimum only establishes eligibility. */
    fun preferredOutput(request: String, profile: PlatformV2, appendContinuations: Boolean = false, userOutputLimit: Int? = null, userContextLimit: Int? = null): Int {
        val minimum = requiredOutput(request, profile, appendContinuations)
        if (!appendContinuations) return minimum
        val usefulPacket = requiredOutput(request, profile).coerceIn(4096, 16_384)
        val contextBound = listOfNotNull(ProviderRouteCapacity.context(profile), userContextLimit?.takeIf { it > 0 }).minOrNull()?.let { maxOf(minimum, it / 4) } ?: Int.MAX_VALUE
        return maxOf(minimum, minOf(ProviderRouteCapacity.output(profile, usefulPacket), contextBound, userOutputLimit?.takeIf { it > 0 } ?: Int.MAX_VALUE))
    }

    fun requiredOutput(request: String, profile: PlatformV2, appendContinuations: Boolean = false): Int {
        val policy = CombinedAnswerPolicy.from(request)
        val requestedWords = policy.maximumWords ?: policy.targetWords ?: policy.minimumWords ?: if (CombinedTaskContract.from(request).action == "CONVERSATION") 30 else 500
        // Append mode reserves a useful passage per request. The visible answer contract
        // is unchanged and is checked after all committed passages have been assembled.
        val words = if (appendContinuations) requestedWords.coerceAtMost(96) else requestedWords
        val observation = CombinedWriterObservations.get(CombinedWriterObservations.key(profile))
        val catalogReasoning = if (profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE) {
            dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(profile.apiUrl)?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.model(it, profile.model)?.reasoning }
        } else {
            null
        }
        val reasoningReserve = when {
            (observation?.reasoningTokensHighWater ?: 0) > 0 -> (observation!!.reasoningTokensHighWater.toLong() * 5 / 4 + 256).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            observation?.reasoningDisabled in setOf("NOT_HONORED", "DISABLE_UNVERIFIED") -> 4096
            observation?.reasoningDisabled == "OBSERVED_NO_REASONING" && observation.completeAnswers > 0 -> 0
            catalogReasoning == false -> 0
            appendContinuations && catalogReasoning != true -> 256
            else -> 4096
        }
        val finalAnswerFloor = if (appendContinuations) 0L else observation?.minimumUsefulOutputAllowance?.toLong() ?: 0L
        val stalledFloor = if (appendContinuations && (observation?.reasoningOnlyAttempts ?: 0) == 0) 0L else observation?.stalledOutputAllowance?.toLong()?.plus(512) ?: 0L
        val framing = if (appendContinuations) 256 else 512
        val minimum = if (appendContinuations) 512L else 768L
        return maxOf((words * 2.2 + framing + reasoningReserve).toLong(), finalAnswerFloor, stalledFloor).coerceIn(minimum, Int.MAX_VALUE.toLong()).toInt()
    }
    fun unavailableReason(profile: PlatformV2, request: String, inputTokens: Int = 0, appendContinuations: Boolean = false): String? {
        val required = requiredOutput(request, profile, appendContinuations)
        val cap = ProviderRouteCapacity.output(profile, required)
        if (cap < required) return "${if (appendContinuations) "Each answer passage" else "Final writing"} needs at least $required generation tokens including record framing; this route allows $cap. Its contributions remain available."
        val observed = CombinedWriterObservations.get(CombinedWriterObservations.key(profile))
        if (observed?.disableRequested == true && observed.reasoningDisabled == "NOT_HONORED") {
            return "This route ignored the request to disable reasoning or exceeded its reported output allowance. Choose another writer or model; saved contributions remain available."
        }
        if ((observed?.reasoningOnlyAttempts ?: 0) >= 2) return "This route exhausted its bounded reasoning recovery without an answer passage. Choose another writer or model; saved contributions remain available."
        if ((observed?.malformedAttempts ?: 0) >= 2) return "This route repeatedly failed structured delivery. Choose another writer; saved passages remain available."
        if ((observed?.queueDelays ?: 0) >= 1) return "This route exceeded the interactive queue wait. Its remote receipt remains available."
        if (inputTokens > 0 && ProviderRouteCapacity.context(profile)?.let { inputTokens.toLong() + required > it } == true) return "The assembled input and final output exceed this model’s known context capacity."
        return null
    }
    fun rank(profiles: List<PlatformV2>, request: String, preferredUid: String?, inputTokens: Int = 0, appendContinuations: Boolean = false): List<PlatformV2> = profiles
        .filter { CombinedProviderEligibility.unavailableReason(it, request, writer = true, appendContinuations = appendContinuations) == null && unavailableReason(it, request, inputTokens, appendContinuations) == null }
        .sortedWith(
            compareBy<PlatformV2> {
                CombinedWriterObservations.get(CombinedWriterObservations.key(it))?.let { o -> o.reasoningOnlyAttempts * 4 + o.malformedAttempts * 5 + o.emptyAttempts * 3 + o.queueDelays * 5 + o.incompleteAnswers * 3 + if (o.disableRequested == true && o.reasoningObserved == true) 2 else 0 } ?: 0
            }.thenBy { it.uid != preferredUid }.thenByDescending { CombinedWriterObservations.get(CombinedWriterObservations.key(it))?.completeAnswers ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.uid }
        )
}
