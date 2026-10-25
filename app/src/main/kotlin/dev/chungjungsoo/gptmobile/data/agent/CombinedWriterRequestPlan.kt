package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.context.ContextBuilder
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.ProviderContextPolicy
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RequestAdmissionReceipt
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedEditorialInstruction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One immutable writer decision over the complete frozen input. The input measurement is
 * explicitly an estimate; the provider serializer still admits its exact bytes before dispatch.
 * That final admission may reject this plan, but may never silently shrink its answer allowance.
 */
@Serializable
data class CombinedWriterRequestPlan(
    val version: Int = 1,
    val planId: String,
    val profileUid: String,
    val model: String,
    val deliveryProtocol: String,
    val promptDigest: String,
    val systemPromptDigest: String,
    val historyDigest: String,
    val routeRevision: String,
    val estimatedInputTokens: Long,
    val uncertaintyTokens: Long,
    val requiredOutputTokens: Int,
    val effectiveOutputTokens: Int,
    val contextTokens: Int?,
    val capacityState: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val preparedContextDigest: String? = null
) {
    fun validate(snapshot: CombinedInputSnapshot) {
        require(version == 1 && planId.isNotBlank()) { "COMBINED_WRITER_PLAN_UNAVAILABLE" }
        require(profileUid == snapshot.profileUid && model == snapshot.model && deliveryProtocol == snapshot.deliveryProtocol) { "COMBINED_WRITER_PLAN_IDENTITY_CHANGED" }
        require(promptDigest == combinedDigest(snapshot.prompt)) { "COMBINED_WRITER_PLAN_PROMPT_CHANGED" }
        require(systemPromptDigest == combinedDigest(combinedWriterSystemPrompt(snapshot.systemPrompt, snapshot.deliveryProtocol, snapshot.userMessage.content))) { "COMBINED_WRITER_PLAN_SYSTEM_CHANGED" }
        require(historyDigest == writerHistoryDigest(snapshot.writerContext?.historyText() ?: combinedWriterHistory(snapshot.history, snapshot.replies, snapshot.profileUid))) { "COMBINED_WRITER_PLAN_HISTORY_CHANGED" }
        require(preparedContextDigest == snapshot.writerContext?.envelopeDigest(snapshot.prompt)) { "COMBINED_WRITER_PLAN_CONTEXT_CHANGED" }
        require(requiredOutputTokens > 0 && effectiveOutputTokens >= requiredOutputTokens) { "COMBINED_WRITER_PLAN_OUTPUT_INSUFFICIENT" }
    }

    fun validateProfile(profile: PlatformV2) {
        require(profile.uid == profileUid && profile.model == model) { "COMBINED_WRITER_PLAN_IDENTITY_CHANGED" }
        require(routeRevision == combinedDigest(ProviderRouteCapacity.key(profile))) { "COMBINED_WRITER_PLAN_ROUTE_CHANGED" }
    }

    /** Validate the model-facing envelope before provider-specific serialization or normalization. */
    fun validatePreparedRequest(profile: PlatformV2, turns: List<ConversationTurn>) {
        validateProfile(profile)
        require(systemPromptDigest == combinedDigest(profile.systemPrompt.orEmpty())) { "COMBINED_WRITER_PLAN_SYSTEM_CHANGED" }
        val current = turns.singleOrNull { it.isCurrentTurn }
        require(current != null && current === turns.last() && current.assistantMessage == null) { "COMBINED_WRITER_PLAN_CURRENT_TURN_CHANGED" }
        require(promptDigest == combinedDigest(current.userMessage.content)) { "COMBINED_WRITER_PLAN_PROMPT_CHANGED" }
        require(historyDigest == writerHistoryDigest(turns.dropLast(1).flatMap { listOf(it.userMessage.content, it.assistantMessage?.content.orEmpty()) })) { "COMBINED_WRITER_PLAN_HISTORY_CHANGED" }
        preparedContextDigest?.let { require(it == CombinedWriterContext.digest(turns)) { "COMBINED_WRITER_PLAN_CONTEXT_CHANGED" } }
    }

    fun validateDispatch(receipt: RequestAdmissionReceipt) {
        check(receipt.runtime.connectionId == profileUid && receipt.runtime.requestedModel == model) { "COMBINED_WRITER_PLAN_IDENTITY_CHANGED: prepare a new writer choice before dispatch" }
        check(receipt.outputTokens == effectiveOutputTokens.toLong()) {
            "COMBINED_OUTPUT_ALLOWANCE_CHANGED: the serialized route allows ${receipt.outputTokens ?: 0} tokens; the frozen writer plan requires $effectiveOutputTokens. No generation was sent."
        }
        check(receipt.outputTokens >= requiredOutputTokens) { "COMBINED_WRITER_PLAN_OUTPUT_INSUFFICIENT" }
    }

    companion object {
        internal fun freeze(
            profile: PlatformV2,
            request: String,
            prompt: String,
            systemPrompt: String,
            history: List<String>,
            protocol: String,
            plannedOutputTokens: Int,
            userOutputLimit: Int? = null,
            userContextLimit: Int? = null,
            writerContext: CombinedWriterContext? = null,
            appendContinuations: Boolean = false
        ): CombinedWriterRequestPlan {
            CombinedProviderEligibility.unavailableReason(profile, request, writer = true, appendContinuations = appendContinuations)?.let { error(it) }
            CombinedWriterPolicy.unavailableReason(profile, request, appendContinuations = appendContinuations)?.let { error(it) }
            val required = CombinedWriterPolicy.requiredOutput(request, profile, appendContinuations)
            val requested = maxOf(required, plannedOutputTokens)
            val output = minOf(ProviderRouteCapacity.output(profile, requested), userOutputLimit?.takeIf { it > 0 } ?: Int.MAX_VALUE)
            require(output >= required) { "The selected writer needs $required generation tokens including reasoning and record framing; its accepted limit is $output. Choose a writer or raise the limit before starting." }
            val context = listOfNotNull(ProviderRouteCapacity.context(profile), userContextLimit?.takeIf { it > 0 && it < Int.MAX_VALUE }).minOrNull()
            val envelope = buildJsonObject {
                put("model", profile.model)
                put("system", systemPrompt)
                put("history", JsonArray(history.map(::JsonPrimitive)))
                put("prompt", prompt)
                put("max_output_tokens", output)
            }.toString()
            val measured = RequestAdmission.estimate(envelope)
            val total = Math.addExact(Math.addExact(measured.promptTokens, measured.uncertaintyTokens), output.toLong())
            if (context != null && total > context) throw CombinedInputCapacityException("The complete frozen input, safety margin and final answer need approximately $total tokens; the selected writer's accepted context is $context. Saved contributions remain available.")
            val digest = combinedDigest(envelope + "|" + protocol + "|" + ProviderRouteCapacity.key(profile))
            return CombinedWriterRequestPlan(
                planId = digest,
                profileUid = profile.uid,
                model = profile.model,
                deliveryProtocol = protocol,
                promptDigest = combinedDigest(prompt),
                systemPromptDigest = combinedDigest(systemPrompt),
                historyDigest = writerHistoryDigest(history),
                routeRevision = combinedDigest(ProviderRouteCapacity.key(profile)),
                estimatedInputTokens = measured.promptTokens,
                uncertaintyTokens = measured.uncertaintyTokens,
                requiredOutputTokens = required,
                effectiveOutputTokens = output,
                contextTokens = context,
                capacityState = if (context == null) "CAPACITY_UNVERIFIED" else "ESTIMATED_FIT_REQUIRES_WIRE_ADMISSION",
                preparedContextDigest = writerContext?.envelopeDigest(prompt)
            )
        }
    }
}

internal class CombinedInputCapacityException(message: String) : IllegalArgumentException(message)

/** Propagates through invocation wrappers to both local and remote physical dispatch. */
internal class CombinedWriterDispatchGuard(val plan: CombinedWriterRequestPlan) : AbstractCoroutineContextElement(Key) {
    private val prepared = AtomicBoolean(false)
    private val admittedPlan = java.util.concurrent.atomic.AtomicReference(plan)

    fun validatePreparedRequest(profile: PlatformV2, turns: List<ConversationTurn>) {
        prepared.set(false)
        admittedPlan.get().validatePreparedRequest(profile, turns)
        prepared.set(true)
    }

    fun validateDispatch(receipt: RequestAdmissionReceipt) {
        check(prepared.get()) { "COMBINED_WRITER_PREPARED_ENVELOPE_UNAVAILABLE" }
        admittedPlan.get().validateDispatch(receipt)
    }

    /** Only an application-owned saved cursor can extend the original complete request. */
    fun authorizeContinuation(snapshot: CombinedInputSnapshot, state: CombinedRecordCheckpoint, profile: PlatformV2, initialTurns: List<ConversationTurn>, continued: List<ConversationTurn>, cursor: String) {
        require(dev.chungjungsoo.gptmobile.BuildConfig.COMBINED_APPEND_CONTINUATIONS && snapshot.combined?.requested?.appendContinuations == true) { "COMBINED_APPEND_NOT_ACCEPTED" }
        require(snapshot.writerRequestPlan == plan && state.snapshotDigest == snapshot.inputDigest && state.appendReady && !state.ended && state.noProgressAttempts < 2) { "COMBINED_APPEND_CURSOR_UNAVAILABLE" }
        require(state.lastAttemptOutcome in setOf("COMPLETED", "OUTPUT_LIMIT", "KNOWN_PROVIDER_REJECTION")) { "COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN" }
        require(state.retryAtMillis?.let { System.currentTimeMillis() >= it } != false) { "COMBINED_APPEND_PROVIDER_WAIT" }
        plan.validate(snapshot)
        plan.validatePreparedRequest(profile, initialTurns)
        require(continued.size == initialTurns.size && continued.dropLast(1) == initialTurns.dropLast(1)) { "COMBINED_APPEND_HISTORY_CHANGED" }
        val original = initialTurns.last()
        val current = continued.last()
        require(current.copy(userMessage = current.userMessage.copy(content = original.userMessage.content)) == original && current.userMessage.content.startsWith(original.userMessage.content + "\n\nResume the frozen structured answer using this application-owned cursor: ")) { "COMBINED_APPEND_CORPUS_CHANGED" }
        val descriptor = Json.parseToJsonElement(cursor) as? JsonObject ?: error("COMBINED_APPEND_CURSOR_INVALID")
        require((descriptor["protocol"] as? JsonPrimitive)?.content == state.protocol && (descriptor["next_sequence"] as? JsonPrimitive)?.intOrNull == state.lastSequence + 1) { "COMBINED_APPEND_CURSOR_INVALID" }
        require((descriptor["committed_ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content } == state.records.map { it.id }) { "COMBINED_APPEND_CURSOR_INVALID" }
        require(continued == dev.chungjungsoo.gptmobile.data.queue.appendFollowUpContext(initialTurns, "\n\nResume the frozen structured answer using this application-owned cursor: $cursor", state.records.joinToString("\n") { it.wire }, emptyList())) { "COMBINED_APPEND_CORPUS_CHANGED" }
        // The original prompt contains every frozen source. Only the durable cursor and
        // committed records are added; provider admission still measures all actual bytes.
        prepared.set(false)
        admittedPlan.set(
            plan.copy(
                promptDigest = combinedDigest(current.userMessage.content),
                preparedContextDigest = plan.preparedContextDigest?.let { CombinedWriterContext.digest(continued) }
            )
        )
    }

    companion object Key : CoroutineContext.Key<CombinedWriterDispatchGuard>
}

/** Shared by the frozen plan, initial writer, explicit retry and successor writer. */
internal fun combinedWriterSystemPrompt(base: String?, protocol: String, request: String): String =
    listOf(base.orEmpty(), combinedEditorialInstruction(protocol, request)).filter(String::isNotBlank).joinToString("\n\n")

/** Match actual history selection, including selected revisions and undelivered Combined turns. */
internal fun combinedWriterHistory(history: List<MessageV2>, replies: List<List<MessageV2>>, profileUid: String): List<String> =
    ContextBuilder().build(
        history + MessageV2(platformType = null, content = "Frozen writer input"),
        replies + listOf(emptyList()),
        PlatformV2(uid = profileUid, name = "Frozen writer"),
        ProviderContextPolicy(recentTurnWindow = Int.MAX_VALUE, historicalImageTurnWindow = Int.MAX_VALUE, maxHistoryCharBudget = Int.MAX_VALUE)
    ).dropLast(1).flatMap { listOf(it.userMessage.content, it.assistantMessage?.content.orEmpty()) }

private fun writerHistoryDigest(history: List<String>): String = combinedDigest(JsonArray(history.map(::JsonPrimitive)).toString())
