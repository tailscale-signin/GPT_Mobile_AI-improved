package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.queue.appendFollowUpContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class CombinedRecordContinuationInput(val turns: List<ConversationTurn>, val recordContextTokens: Int)

/** Keeps every frozen source while allowing the locally retained answer to exceed one request. */
internal fun combinedRecordContinuationInput(
    initialTurns: List<ConversationTurn>,
    state: CombinedRecordCheckpoint,
    cursor: String,
    systemPrompt: String,
    settings: TokenBudgetSettings,
    outputTokens: Int
): CombinedRecordContinuationInput {
    require(outputTokens > 0) { "A structured continuation needs its accepted output allowance." }
    val config = settings.normalized().copy(outputTokens = outputTokens)
    // Include the follow-up tail label in the fixed request cost before assigning its
    // remaining space. Full source input and the cursor are never silently shortened.
    val fixed = appendFollowUpContext(initialTurns, combinedRecordContinuationInstruction(cursor, outputTokens), "x")
    val fixedCost = ContextBudgetService.breakdown(fixed, systemPrompt, emptyList()).total.toLong()
    val reserve = outputTokens.takeIf { it > 0 } ?: minOf(4096, config.contextTokens / 4)
    val available = if (config.contextTokens == Int.MAX_VALUE) {
        4096
    } else {
        (config.contextTokens.toLong() - reserve - minOf(256, config.contextTokens / 16) - fixedCost).coerceIn(0, 4096).toInt()
    }
    val turns = appendFollowUpContext(initialTurns, combinedRecordContinuationInstruction(cursor, outputTokens), combinedRecordContinuationView(state, available))
    ContextBudgetService.planFullInput(turns, systemPrompt, config)
    return CombinedRecordContinuationInput(turns, available)
}

internal fun combinedRecordContinuationInstruction(cursor: String, outputTokens: Int): String {
    val words = ((outputTokens.coerceAtLeast(256) - 256) / 2.2).toInt().coerceIn(12, 96)
    return "\n\nResume the frozen structured answer using this application-owned cursor: $cursor" +
        "\nThis request allows $outputTokens output tokens. Keep each passage within $words visible words and complete its JSON record. " +
        "Do not emit the end record until the entire requested answer is complete. The saved cursor preserves earlier records even when only their bounded text context is shown."
}

internal fun combinedRecordDigest(state: CombinedRecordCheckpoint): String =
    combinedDigest(state.records.joinToString("\n") { "${it.sequence}:${it.id}:${it.digest}" })

internal fun combinedRecordVisibleText(state: CombinedRecordCheckpoint): String = state.records.mapNotNull { record ->
    require(record.digest == combinedDigest(record.wire)) { "COMBINED_CHECKPOINT_INTEGRITY" }
    val row = Json.parseToJsonElement(record.wire) as JsonObject
    if ((row["type"] as? JsonPrimitive)?.content == "passage") (row["text"] as? JsonPrimitive)?.content else null
}.joinToString("\n\n")

internal fun combinedRecordContinuationView(state: CombinedRecordCheckpoint, maxTokens: Int): String =
    LongResponsePolicy.continuationContext(combinedRecordVisibleText(state), maxTokens)
