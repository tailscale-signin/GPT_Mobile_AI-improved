package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRecordContinuationInputTest {
    @Test fun boundedGeneratedAnswerViewRetainsTheFullFrozenTaskAndSourceInput() {
        val frozen = "Original task: 5000 words. Source A with exact date 1385. Source B with exact date 1974."
        val original = listOf(ConversationTurn(MessageV2(platformType = null, content = frozen), null, true))
        val protocol = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = "frozen", revisionId = "revision"), emptySet(), emptySet(), emptySet(), 100000)
        repeat(30) { number ->
            protocol.accept(
                buildJsonObject {
                    put("type", "passage")
                    put("seq", number + 1)
                    put("id", "P${number + 1}")
                    put("text", "Historical section $number: " + "retained details about Portugal ".repeat(100))
                    put("claims", JsonArray(emptyList()))
                    put("contributors", JsonArray(emptyList()))
                    put("evidence", JsonArray(emptyList()))
                }.toString()
            )
        }
        protocol.markAttemptFinished("OUTPUT_LIMIT")
        val cursor = protocol.continuationContext()
        val state = protocol.checkpoint()
        val limits = TokenBudgetSettings(contextTokens = 4096, outputTokens = 1024)
        val input = combinedRecordContinuationInput(original, state, cursor, "System", limits, 1024)
        val content = input.turns.single().userMessage.content
        assertTrue(content.startsWith(frozen))
        assertEquals(30, state.records.size)
        assertTrue(content.length < state.records.sumOf { it.wire.length })
        assertTrue(content.endsWith("retained details about Portugal "))
        assertTrue(content.contains("96 visible words"))
        assertTrue(content.contains("This request allows 1024 output tokens"))
        assertTrue(ContextBudgetService.planFullInput(input.turns, "System", limits).promptTokens + 1024 + 256 <= 4096)
        val descriptor = Json.parseToJsonElement(cursor) as JsonObject
        assertEquals(30, (descriptor["committed_ids"] as JsonArray).size)
        assertEquals(combinedRecordDigest(state), (descriptor["committed_digest"] as JsonPrimitive).content)
        assertEquals("frozen", (descriptor["snapshot_digest"] as JsonPrimitive).content)
    }
}
