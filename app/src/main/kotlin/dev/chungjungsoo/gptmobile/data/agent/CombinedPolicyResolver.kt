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
        acceptedResearch: CombinedResearchRequirement? = null,
        singlePass: Boolean = false
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
            acceptedResearch = acceptedResearch,
            singlePass = singlePass
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
        val draftOutput = maxOf(listOf(512, 1024, 2048, 4096, 8192)[stop], if (requested.acceptedResearch != null) 4096 else 0)
        val defaultFinal = listOf(700, 1400, 2500, 5000, 12_000)[stop]
        val explicitWords = Regex("(?i)\\b(?:exactly\\s+|about\\s+|approximately\\s+)?(\\d{1,7})[ -]+words?\\b")
            .find(requested.userPrompt)?.groupValues?.get(1)?.toLongOrNull()
        val visibleGuide = explicitWords?.let { (it * 2L + 128).coerceAtMost(Int.MAX_VALUE.toLong()) } ?: defaultFinal.toLong()
        // PASSAGES_V3 includes decision and framing records in the provider output cap.
        // This capacity never changes the visible word contract or an explicit caller cap.
        val recordAllowance = maxOf(512L, visibleGuide / 3) + minOf(1024L, (requested.acceptedResearch?.sourceGoal ?: 0L) * 64L)
        val reasoningReserve = 4096L
        val requestedFinal = requiredOutputTokens?.coerceAtLeast(1)
            ?: Math.addExact(visibleGuide, recordAllowance + reasoningReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val input = estimatedInputTokens.toLong().coerceAtLeast(256)
        val availableOutput = hardLimits.generatedTokens ?: Long.MAX_VALUE
        val finalTokens = minOf(requestedFinal.toLong(), availableOutput).coerceAtLeast(0)
        // Research editing includes the compact contribution inventory and registered evidence,
        // not just the original user prompt. Reserve these inputs before dispatching workers.
        val editorialInput = if (requested.acceptedResearch != null) Math.addExact(input, Math.addExact(Math.multiplyExact(requested.participantUids.size.toLong(), draftOutput.toLong()), 8000L)) else input
        val finalInput = minOf(editorialInput, hardLimits.inputTokens ?: Long.MAX_VALUE)
        // Single-pass turns cannot spend a second generation; protect exactly one writer.
        val finalPasses = if (requested.singlePass) 1 else 2
        val proposedFinalReserve = CombinedResourceQuota(
            minOf(finalInput * finalPasses, hardLimits.inputTokens ?: Long.MAX_VALUE),
            minOf(finalTokens * finalPasses, availableOutput),
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
            // Report delivery, one correction and one output-limit completion are required
            // contributor work. They never consume the protected final editor allocation.
            val reportPasses = if (requested.acceptedResearch != null) 3L else 1L
            val reportInput = if (requested.acceptedResearch != null) Math.addExact(input, 8000L) else input
            val quota = CombinedResourceQuota(reportInput, draftOutput.toLong()).scale(reportPasses)
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
        val discoveryCalls = requested.acceptedResearch?.let { minOf(it.sourceGoal, 3L) * maxOf(1, requested.participantUids.size) } ?: 0L
        val mandatoryCalls = if (toolsAllowed) maxOf(requiredToolOperations, Math.addExact(explicitAcquisitions, discoveryCalls)) else 0
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
        val researchStages = mutableListOf<CombinedWorkItem>()
        val unfundedStages = linkedSetOf<String>()
        requested.acceptedResearch?.let { research ->
            requested.participantUids.filterNot { it in blocked }.forEach { uid ->
                CombinedResearchStages.names.filter { stage ->
                    when (stage) {
                        "appraisal", "study_review" -> research.examinationRequested
                        "independent_review" -> research.examinationRequested && research.independentReviewRequested
                        else -> true
                    }
                }.forEach { stage ->
                    val units = when (stage) {
                        "extract", "review" -> research.sourceGoal
                        "study_review" -> Math.multiplyExact(research.sourceGoal, 11L)
                        "appraisal", "independent_review" -> Math.multiplyExact(research.sourceGoal, 3L)
                        "find_gaps" -> maxOf(1L, (research.depth + 24L) / 25L)
                        else -> 1L
                    }
                    val id = CombinedResearchStages.id(uid, stage)
                    researchStages += CombinedWorkItem(id, CombinedRunRole.RESEARCH, units, reason = "ACCEPTED_RESEARCH_STAGE", assignedOwnerKey = id)
                    val stageInput = when (stage) {
                        "appraisal", "independent_review" -> 12000L
                        "study_review" -> 16000L
                        else -> 1024L
                    }
                    val quota = CombinedResourceQuota(Math.addExact(input, stageInput), CombinedResearchStages.outputTokens(stage).toLong()).scale(units)
                    val cost = prices[uid]?.cost(quota.inputTokens, quota.generatedTokens)
                    if (admissible(quota) && (hardLimits.costMicros?.let { cost != null && cost <= it - usedCost } ?: true)) {
                        baseline[id] = quota
                        cost?.let {
                            costReserves[id] = it
                            usedCost = Math.addExact(usedCost, it)
                        }
                        used += quota
                    } else {
                        unfundedStages += id
                    }
                }
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
                listOf(CombinedWorkItem("verification", CombinedRunRole.VERIFIER, maxOf(1, researchCalls), required = false, reason = "OPTIONAL_VERIFICATION_RESERVE")) +
                listOf(CombinedWorkItem("finalization", CombinedRunRole.SYNTHESIS)) + researchStages +
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
            unfundedRequiredWorkIds = (if (mandatoryFunded) emptySet() else mandatoryEvidence.mapTo(linkedSetOf()) { it.id }) + unfundedStages,
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
