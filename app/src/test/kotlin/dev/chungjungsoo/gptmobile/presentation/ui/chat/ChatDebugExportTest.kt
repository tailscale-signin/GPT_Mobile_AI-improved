package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDebugExportTest {
    @Test fun fullRawExportRetainsPromptsThinkingRevisionsAndExactResponseRunMapping() {
        val answer = MessageV2(
            id = 2,
            chatId = 1,
            content = "Organized final answer",
            thoughts = "Retained reasoning",
            platformType = "lead",
            linkedMessageId = 1,
            currentRunId = "combined-synthesis:run",
            revisions = listOf(AssistantRevision("Historical answer", createdAt = 1, runId = "earlier-run")),
            activeRevisionIndex = 0,
            combinedSources = listOf(CombinedModelResponse("lead", "Lead", "model", "Unique contributor fact"))
        )
        val messages = listOf(MessageV2(id = 1, chatId = 1, content = "Original prompt", platformType = null), answer) +
            (3..200).map { MessageV2(id = it, chatId = 1, content = "Older turn $it", platformType = null) }
        val body = buildChatDebugExport(
            ChatRoomV2(id = 1, title = "Test"), messages,
            listOf(PlatformV2(uid = "lead", name = "Lead", token = "configured-private-secret", secretRef = "credential-ref")),
            ChatMcpToolConfig(), AppFeatureSettings(), "Draft prompt",
            buildJsonObject {}, emptyList(), Json { encodeDefaults = true }
        )
        val raw = Json.parseToJsonElement(body).jsonObject
        assertEquals(200, raw.getValue("messages").jsonArray.size)
        assertTrue(body.contains("Original prompt"))
        assertTrue(body.contains("Retained reasoning"))
        assertTrue(body.contains("Unique contributor fact"))
        assertFalse(body.contains("configured-private-secret"))
        assertFalse(body.contains("credential-ref"))
        val mapping = raw.getValue("responseMapping").jsonArray.single().jsonObject
        assertEquals("combined-synthesis:run", mapping.getValue("runId").jsonPrimitive.content)
        assertEquals(debugBodyDigest(answer.content), mapping.getValue("bodySha256").jsonPrimitive.content)
        assertEquals(debugBodyDigest("Historical answer"), mapping.getValue("selectedBodySha256").jsonPrimitive.content)
        assertEquals("earlier-run", mapping.getValue("revisionMapping").jsonArray.single().jsonObject.getValue("runId").jsonPrimitive.content)
    }
}
