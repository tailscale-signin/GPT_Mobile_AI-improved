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
    @Test fun segmentExportHashesAndByteCountsDescribeOnlyRedactedText() {
        val text = "Credential: configured-secret-value"
        val segment = dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment("run", 0, 2, text, debugBodyDigest(text), null, text.toByteArray().size)
        val json = Json { encodeDefaults = true }
        val records = buildJsonObject {
            put("answerSegments", kotlinx.serialization.json.JsonArray(listOf(json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment.serializer(), segment))))
        }
        val body = buildChatDebugExport(ChatRoomV2(id = 1), emptyList(), listOf(PlatformV2(name = "Profile", token = "configured-secret-value")), ChatMcpToolConfig(), AppFeatureSettings(), "", records, emptyList(), json)
        assertFalse(body.contains("configured-secret-value"))
        assertFalse(body.contains(segment.digest))
        val exported = Json.parseToJsonElement(body).jsonObject.getValue("answerSegments").jsonArray.single().jsonObject
        val redacted = exported.getValue("text").jsonPrimitive.content
        assertEquals(debugBodyDigest(redacted), exported.getValue("digest").jsonPrimitive.content)
        assertEquals(redacted.toByteArray().size.toString(), exported.getValue("utf8Bytes").jsonPrimitive.content)
    }

    @Test fun exportedDigestMatchesRedactedBodyRatherThanPrivateOriginal() {
        val message = MessageV2(id = 2, chatId = 1, platformType = "lead", content = "Bearer test-private-credential")
        val body = buildChatDebugExport(ChatRoomV2(id = 1, title = "Synthetic"), listOf(message), listOf(PlatformV2(uid = "lead", name = "Lead")), ChatMcpToolConfig(), AppFeatureSettings(), "", buildJsonObject {}, emptyList(), Json { encodeDefaults = true })
        val raw = Json.parseToJsonElement(body).jsonObject
        val exported = raw.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
        val mapping = raw.getValue("responseMapping").jsonArray.single().jsonObject
        assertFalse(body.contains("test-private-credential"))
        assertEquals(debugBodyDigest(exported), mapping.getValue("bodySha256").jsonPrimitive.content)
        assertEquals("redacted-export-body-utf8", mapping.getValue("digestRepresentation").jsonPrimitive.content)
    }

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
