package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugPolicySnapshotsTest {
    @Test fun actualProducerStoresOnePolicyAndPlanForNineRunsAndLegacyContentResolvesExactly() {
        val profiles = (1..9).map { "p$it" }
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), profiles, profiles.associateWith { "model" }, "Retain the full accepted request")
        val plan = CombinedPolicyResolver.resolve(requested)
        val original = profiles.associateWith { profile -> CombinedAcceptedTurn(context = CombinedExecutionContext(requested, plan, "root", profileUid = profile), tools = ChatMcpToolConfig()).encode() }
        val records = buildJsonObject {
            put(
                "runs",
                JsonArray(
                    profiles.map { profile ->
                        buildJsonObject {
                            put("runId", profile)
                            put("synthesisSnapshot", original.getValue(profile))
                        }
                    }
                )
            )
        }
        val body = buildChatDebugExport(ChatRoomV2(id = 1, title = "Snapshot fixture"), emptyList(), emptyList(), ChatMcpToolConfig(), AppFeatureSettings(), "", records, emptyList(), Json { encodeDefaults = true })
        val exported = Json.parseToJsonElement(body).jsonObject
        val table = exported.getValue("policySnapshots").jsonObject
        val entries = table.getValue("entries").jsonObject
        assertEquals(2, entries.size)
        assertEquals(setOf("COMBINED_REQUESTED_POLICY", "COMBINED_WORK_PLAN"), entries.values.map { it.jsonObject.getValue("kind").jsonPrimitive.content }.toSet())
        exported.getValue("runs").jsonArray.forEach { value ->
            val row = value.jsonObject
            val profile = row.getValue("runId").jsonPrimitive.content
            val expanded = DebugPolicySnapshots.resolveSnapshot(row.getValue("synthesisSnapshot").jsonPrimitive.content, table)
            assertEquals(Json.parseToJsonElement(original.getValue(profile)), expanded)
            assertEquals(expanded, DebugPolicySnapshots.resolveSnapshot(original.getValue(profile)))
        }
    }

    @Test fun redactedPayloadsAreDeduplicatedAndTamperedOrMissingReferencesFailClosed() {
        val secret = "configured-secret-value"
        val normalizer = DebugPolicySnapshots { dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(it, listOf(secret)) }
        val record = buildJsonObject {
            put(
                "requested",
                buildJsonObject {
                    put("requestedPolicyDigest", "original-digest")
                    put("participantUids", JsonArray(listOf(JsonPrimitive("p"))))
                    put("userPrompt", "Private $secret")
                }
            )
        }
        val normalized = normalizer.normalize(record)
        assertEquals(normalized, normalizer.normalize(record))
        val table = normalizer.table()
        assertTrue(secret !in table.toString())
        val restored = DebugPolicySnapshots.resolve(normalized, table)
        assertTrue(restored.toString().contains("[redacted]"))
        assertTrue(runCatching { DebugPolicySnapshots.resolve(normalized) }.isFailure)
        val entries = table.getValue("entries").jsonObject
        val key = entries.keys.single()
        val tampered = JsonObject(table + ("entries" to JsonObject(mapOf(key to JsonObject(entries.getValue(key).jsonObject + ("payload" to JsonObject(emptyMap())))))))
        assertTrue(runCatching { DebugPolicySnapshots.resolve(normalized, tampered) }.isFailure)
    }
}
