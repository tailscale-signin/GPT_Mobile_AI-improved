package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.provider.LocalPromptToolBridge
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A closed fixture witness, never an arbitrary prompt or a general custom-term override. */
internal class PrivateSyntheticInput private constructor(
    val case: PrivacyComparisonCase,
    val profile: PlatformV2
) {
    companion object {
        fun create(caseId: String, profile: PlatformV2): PrivateSyntheticInput {
            val case = PrivacyComparisonCase.fromId(caseId)
                ?: throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.UNSUPPORTED_PROFILE)
            return PrivateSyntheticInput(case, privacyComparisonProfile(profile))
        }
    }

    private val source = MessageV2(id = 1, content = case.prompt, platformType = null, createdAt = 0)
    val turns = listOf(ConversationTurn(source, null, true))

    // The private local-tool wrapper adds this exact instruction for zero tools.
    // Apply it to the ordinary remote arm too, keeping the task instructions equal.
    val providerProfile = if (profile.compatibleType == ClientType.LITERT_LM) {
        profile
    } else {
        profile.copy(systemPrompt = profile.systemPrompt.orEmpty() + LocalPromptToolBridge.instruction(emptyList()))
    }

    @Volatile private var inputReceipt: PrivacyProtectionReceipt? = null

    @Volatile private var maskingNanos: Long? = null

    @Volatile private var proof: PrivateDispatchContext? = null

    @Volatile private var dispatch: RequestDispatchContext? = null

    @Volatile private var firstOrdinaryAnswerMillis: Long? = null

    @Volatile private var routeKind: String? = null

    @Volatile private var comparisonLimitation: String? = null

    val protection = PrivateProtectionContext { message, receipt ->
        check(message == source && receipt.validFor(case.prompt))
        inputReceipt = receipt
    }

    fun validate(profile: PlatformV2, turns: List<ConversationTurn>) {
        if (profile != providerProfile || turns != this.turns) {
            throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.UNSUPPORTED_PROFILE)
        }
    }

    /** Evaluated at the ordinary masking load point, after the captured policy checks. */
    fun privateTerms(profile: PlatformV2, turns: List<ConversationTurn>): List<String> {
        validate(profile, turns)
        return case.customTerms
    }

    fun prepared(proof: PrivateDispatchContext, elapsedNanos: Long) {
        check(this.proof == null)
        this.proof = proof
        maskingNanos = elapsedNanos.coerceAtLeast(0)
    }

    fun observeDispatch(dispatch: RequestDispatchContext?) {
        this.dispatch = dispatch
    }

    fun route(kind: String) {
        require(kind in setOf("ORDINARY_TEXT", "MASKED_TEXT", "STRICT_PRIVATE_TEXT", "LOCAL"))
        routeKind = kind
        if (kind == "STRICT_PRIVATE_TEXT") comparisonLimitation = "STRICT_ROUTE_SETTINGS_UNVERIFIED"
    }

    fun ordinaryAnswer(elapsedNanos: Long) {
        if (firstOrdinaryAnswerMillis == null) firstOrdinaryAnswerMillis = elapsedNanos.coerceAtLeast(0) / 1_000_000
    }

    fun measurements(): PrivacyComparisonMeasurements {
        val stream = proof?.streamSnapshot()
        val proofId = (stream?.get("private_request_id") as? JsonPrimitive)?.content
        val wire = proofId?.let { id ->
            dispatch?.privacyDispatchReceipts?.singleOrNull { (it["private_request_id"] as? JsonPrimitive)?.content == id }
        }
        return PrivacyComparisonMeasurements(
            inputReceipt = inputReceipt,
            maskingNanos = maskingNanos,
            restorationNanos = (stream?.get("restoration_ns") as? JsonPrimitive)?.longOrNull,
            firstUpstreamAnswerMillis = (stream?.get("first_upstream_answer_ms") as? JsonPrimitive)?.longOrNull ?: firstOrdinaryAnswerMillis,
            firstRestoredAnswerMillis = (stream?.get("first_restored_answer_ms") as? JsonPrimitive)?.longOrNull,
            wireOutcome = (wire?.get("outcome") as? JsonPrimitive)?.content,
            routeKind = routeKind,
            comparisonLimitation = comparisonLimitation
        )
    }
}
