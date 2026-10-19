package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One policy resolver for every Combined role; slider values never confer capabilities. */
object CombinedPolicyResolver {
    fun accept(
        settings: ConversationCombinedSettings,
        participantUids: List<String>,
        requestedModels: Map<String, String>,
        userPrompt: String,
        acceptedAtMillis: Long = System.currentTimeMillis(),
        settingsRevision: Long = settings.revision,
        preferredOwnerUid: String? = null,
        requestedTemperatures: Map<String, Float?> = emptyMap(),
        acceptedResearch: CombinedResearchRequirement? = null
    ): CombinedRequestedPolicy {
        require(settings.isSupported) { "This conversation uses a newer Combined settings version." }
        val participants = participantUids.filter(String::isNotBlank).distinct()
        require(participants.isNotEmpty()) { "Select at least one Combined participant." }
        val requested = CombinedRequestedPolicy(
            settings = settings.normalized(),
            participantUids = participants,
            requestedModels = requestedModels.filterKeys { it in participants }.toSortedMap(),
            requestedTemperatures = requestedTemperatures.filterKeys { it in participants }.mapValues { (_, temperature) -> temperature?.takeIf { it.isFinite() }?.coerceIn(0f, 2f) }.toSortedMap(),
            preferredOwnerUid = preferredOwnerUid?.takeIf { it in participants },
            userPrompt = userPrompt,
            acceptedAtMillis = acceptedAtMillis,
            settingsRevision = settingsRevision,
            acceptedResearch = acceptedResearch
        )
        return requested.copy(requestedPolicyDigest = combinedDigest(Json.encodeToString(requested)))
    }

    fun resolve(
        requested: CombinedRequestedPolicy,
        estimatedInputTokens: Int = 4096,
        requiredToolOperations: Long = 0,
        requiredOutputTokens: Int? = null,
        toolsAllowed: Boolean = true,
        hardLimits: CombinedHardLimits = CombinedHardLimits(),
        caps: List<CombinedCapRecord> = emptyList(),
        workItems: List<CombinedWorkItem> = emptyList(),
        prices: Map<String, dev.chungjungsoo.gptmobile.data.accounting.ModelPrice> = emptyMap()
    ): CombinedWorkPlan {
        require(requested.version == 1 && requested.settings.isSupported) { "Unsupported Combined policy version." }
        require(requested.requestedPolicyDigest == combinedDigest(Json.encodeToString(requested.copy(requestedPolicyDigest = "")))) { "Combined accepted policy digest changed." }
        require(estimatedInputTokens >= 0 && requiredToolOperations >= 0)
        val stop = requested.settings.normalized().effort / 25
        val optional = listOf(
            CombinedResourceQuota(8_000, 2_000, 2),
            CombinedResourceQuota(32_000, 8_000, 6),
            CombinedResourceQuota(128_000, 32_000, 20),
            CombinedResourceQuota(512_000, 128_000, 80),
            CombinedResourceQuota(2_000_000, 512_000, 300)
        )[stop]
        val draftOutput = listOf(512, 1024, 2048, 4096, 8192)[stop]
        val defaultFinal = listOf(700, 1400, 2500, 5000, 12_000)[stop]
        val explicitWords = Regex("(?i)\\b(?:exactly\\s+|about\\s+|approximately\\s+)?(\\d{1,7})[ -]+words?\\b")
            .find(requested.userPrompt)?.groupValues?.get(1)?.toLongOrNull()
        val requestedFinal = requiredOutputTokens?.coerceAtLeast(1)
            ?: explicitWords?.let { (it * 2L + 128).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            ?: defaultFinal
        val input = estimatedInputTokens.toLong().coerceAtLeast(256)
        val availableOutput = hardLimits.generatedTokens ?: Long.MAX_VALUE
        val finalTokens = minOf(requestedFinal.toLong(), availableOutput).coerceAtLeast(0)
        val finalInput = minOf(input, hardLimits.inputTokens ?: Long.MAX_VALUE)
        // Protect a feasible final before admitting initial contributors. One correction is sealed.
        val proposedFinalReserve = CombinedResourceQuota(
            minOf(finalInput * 2, hardLimits.inputTokens ?: Long.MAX_VALUE),
            minOf(finalTokens * 2, availableOutput),
            0
        )
        val totalCeiling = hardLimits.totalTokens ?: Long.MAX_VALUE
        val reservedInput = minOf(proposedFinalReserve.inputTokens, totalCeiling)
        val finalReserve = proposedFinalReserve.copy(
            inputTokens = reservedInput,
            generatedTokens = minOf(proposedFinalReserve.generatedTokens, totalCeiling - reservedInput)
        )
        var used = finalReserve
        val eligiblePrices = prices.filterKeys { it in requested.participantUids }.values
        val costReserves = linkedMapOf<String, Long>()
        eligiblePrices.maxOfOrNull { it.cost(finalReserve.inputTokens, finalReserve.generatedTokens) }?.let { costReserves["finalization"] = it }
        var usedCost = costReserves["finalization"] ?: 0
        val baseline = linkedMapOf<String, CombinedResourceQuota>()
        val blocked = linkedSetOf<String>()
        fun admissible(quota: CombinedResourceQuota): Boolean =
            quota.inputTokens <= (hardLimits.inputTokens ?: Long.MAX_VALUE) - used.inputTokens &&
                quota.generatedTokens <= (hardLimits.generatedTokens ?: Long.MAX_VALUE) - used.generatedTokens &&
                quota.inputTokens + quota.generatedTokens <= totalCeiling - used.inputTokens - used.generatedTokens &&
                quota.toolOperations <= (hardLimits.toolOperations ?: Long.MAX_VALUE) - used.toolOperations
        requested.participantUids.forEach { uid ->
            val quota = CombinedResourceQuota(input, draftOutput.toLong())
            val cost = prices[uid]?.cost(quota.inputTokens, quota.generatedTokens)
            val funded = hardLimits.costMicros?.let { cost != null && cost <= it - usedCost } ?: true
            if (admissible(quota) && funded) {
                baseline["contributor:$uid"] = quota
                cost?.let {
                    costReserves["contributor:$uid"] = it
                    usedCost = Math.addExact(usedCost, it)
                }
                used += quota
            } else {
                blocked += uid
            }
        }
        val derivedEvidence = CombinedWorkInventory.derive(requested, toolsAllowed)
        val mandatoryEvidence = derivedEvidence.filter { it.required }
        val requiredUnits = mandatoryEvidence.sumOf { it.remainingUnits }
        val explicitAcquisitions = mandatoryEvidence.sumOf { item ->
            val direct = minOf(item.remainingUnits, item.sourceUrls.size.toLong())
            direct + (item.remainingUnits - direct) * 2
        }
        val mandatoryCalls = if (toolsAllowed) maxOf(requiredToolOperations, explicitAcquisitions) else 0
        val fundingOrder = listOfNotNull(requested.preferredOwnerUid) + requested.participantUids
        val fundingUid = fundingOrder.distinct().firstOrNull { uid ->
            "contributor:$uid" in baseline && caps.none { it.profileUid == uid && it.name == "profileMaxToolCalls" && it.value == 0L }
        }
        val fundingOwner = fundingUid?.let { "contributor:$it" }
        var mandatoryFunded = mandatoryCalls == 0L && (toolsAllowed || mandatoryEvidence.isEmpty())
        if (fundingOwner != null && mandatoryCalls > 0) {
            // This is one turn-wide assignment, not N copies for N contributors. Reserve enough
            // bounded request replays even if a provider makes one acquisition per round.
            val replayInput = Math.multiplyExact(Math.addExact(input, Math.multiplyExact(requiredUnits, 512L)), mandatoryCalls)
            val quota = CombinedResourceQuota(replayInput, Math.multiplyExact(maxOf(1024, draftOutput).toLong(), mandatoryCalls), mandatoryCalls)
            val cost = prices[fundingUid]?.cost(quota.inputTokens, quota.generatedTokens)
            if (admissible(quota) && (hardLimits.costMicros?.let { cost != null && cost <= it - usedCost } ?: true)) {
                baseline[fundingOwner] = requireNotNull(baseline[fundingOwner]) + quota
                cost?.let {
                    costReserves[fundingOwner] = Math.addExact(costReserves[fundingOwner] ?: 0, it)
                    usedCost = Math.addExact(usedCost, it)
                }
                used += quota
                mandatoryFunded = true
            }
        }
        val researchCalls = 0L // Explicit source work was funded once for its assigned contributor.
        val verification = CombinedResourceQuota(input, 1024, researchCalls)
        val verificationCost = eligiblePrices.maxOfOrNull { it.cost(verification.inputTokens, verification.generatedTokens) }
        if (admissible(verification) && (hardLimits.costMicros?.let { verificationCost != null && verificationCost <= it - usedCost } ?: true)) {
            baseline["verification"] = verification
            verificationCost?.let { costReserves["verification"] = it }
            used += verification
        }
        val searchAllowed = !Regex("(?i)\\b(?:do not|don't|never|without)\\s+(?:web[ -]?)?(?:search|browse)(?:ing)?\\b|\\bno (?:web |internet )?search\\b")
            .containsMatchIn(requested.userPrompt)
        val finite = workItems.ifEmpty {
            requested.participantUids.filterNot { it in blocked }.map { CombinedWorkItem("contributor:$it", CombinedRunRole.CONTRIBUTOR) } +
                listOf(CombinedWorkItem("verification", CombinedRunRole.VERIFIER, maxOf(1, researchCalls))) +
                listOf(CombinedWorkItem("finalization", CombinedRunRole.SYNTHESIS)) +
                derivedEvidence.map { item -> if (item.required) item.copy(assignedOwnerKey = fundingOwner) else item }
        }
        require(finite.map { it.id }.distinct().size == finite.size && finite.all { it.id.isNotBlank() && it.remainingUnits > 0 })
        val plan = CombinedWorkPlan(
            requested = requested,
            baselineByOwner = baseline,
            finalizationReserve = finalReserve,
            optionalAllowance = optional.copy(toolOperations = if (toolsAllowed) optional.toolOperations else 0),
            costReservesByOwner = costReserves,
            hardLimits = hardLimits,
            caps = caps + listOf(
                CombinedCapRecord("optional_tool_operations", optional.toolOperations, CombinedCapOrigin.APPLICATION_DEFAULT, CombinedCapScope.COMBINED_TURN, "Effort planning tranche; Full scale can renew for concrete unfinished work"),
                CombinedCapRecord("final_visible_output_guidance", requestedFinal.toLong(), CombinedCapOrigin.APPLICATION_DEFAULT, CombinedCapScope.INVOCATION, "Explicit answer contract overrides default visible-length guidance")
            ),
            workItems = finite,
            blockedParticipantUids = blocked,
            unfundedRequiredWorkIds = if (mandatoryFunded) emptySet() else mandatoryEvidence.mapTo(linkedSetOf()) { it.id },
            initialDraftOutputTokens = draftOutput,
            finalOutputTokens = finalTokens.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            toolsAllowed = toolsAllowed,
            searchAllowed = searchAllowed,
            renewable = stop == 4,
            maxParallelToolCalls = listOf(2, 3, 6, 6, 6)[stop]
        )
        return plan.copy(effectivePlanDigest = combinedDigest(Json.encodeToString(plan)))
    }

    /** Remove only the app's 24/64 clamp. An ambiguous legacy profile cap remains visible and honored. */
    fun toolCallLimit(context: CombinedExecutionContext, profileLimit: Int, chatLimit: Int? = null): Int {
        val plan = requireNotNull(context.plan) { "Resolve Combined policy before dispatch." }
        if (!plan.toolsAllowed || context.role.finalization) return 0
        val workerCap = minOf(profileLimit.coerceAtLeast(0), chatLimit?.coerceAtLeast(0) ?: Int.MAX_VALUE)
        val turnCap = plan.hardLimits.toolOperations?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
        // Optional effort is enforced by the shared ledger, not a second immutable worker cap.
        // A concrete required correction can add sealed work after the initial plan was accepted.
        return minOf(workerCap, turnCap)
    }

    fun evidenceWeights(collectionBias: Int): Pair<Int, Int> {
        val stop = collectionBias.coerceIn(0, 100) / 25
        return (5 - stop) to (1 + stop)
    }
}
