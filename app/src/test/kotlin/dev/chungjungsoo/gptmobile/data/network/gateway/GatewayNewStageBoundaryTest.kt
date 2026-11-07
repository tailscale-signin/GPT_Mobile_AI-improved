package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.dto.openai.common.ImageContent
import dev.chungjungsoo.gptmobile.data.dto.openai.common.ImageUrl
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunction
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayNewStageBoundaryTest {
    private val original = ChatMessage(Role.USER, listOf(TextContent("Original task")))
    private val current = ChatMessage(Role.USER, listOf(TextContent("Continue unfinished work"), ImageContent(ImageUrl("https://example.test/attached.png"))))
    private val call = ChatMessage(Role.ASSISTANT, toolCalls = listOf(ChatToolCall("opaque /call", ChatFunction("read_file", "{\"path\":\"README.md\"}"))))

    @Test fun actualLatestUserAndAttachmentsMoveOnceAfterNativeSuccessOrFailureHistory() {
        for (content in listOf("retained success", "TOOL_FAILED: retained failure")) {
            val result = ChatMessage(Role.TOOL, listOf(TextContent(content)), toolCallId = "opaque /call")
            val media = ChatMessage(Role.USER, listOf(TextContent("Untrusted tool image")))
            val reordered = gatewayNewStageBoundary(listOf(original, current), listOf(call, result, media))
            assertEquals(listOf(original, call, result, media, current), reordered)
            assertSame(current, reordered.last())
            assertSame(result, reordered[2])
            assertEquals(1, reordered.count { it === current })
        }
    }

    @Test fun missingActualUserOrOriginalAnchorIsRejectedBeforeDispatch() {
        for (conversation in listOf(emptyList(), listOf(ChatMessage(Role.SYSTEM, listOf(TextContent("Instruction")))), listOf(current))) {
            assertTrue(runCatching { gatewayNewStageBoundary(conversation, listOf(call, ChatMessage(Role.TOOL, listOf(TextContent("retained")), toolCallId = "opaque /call"))) }.exceptionOrNull()?.message.orEmpty().startsWith("GATEWAY_NEW_STAGE_BOUNDARY_REQUIRED"))
        }
    }

    @Test fun aStageWithoutRetainedToolHistoryKeepsTheActualSingleUserFrame() {
        assertEquals(listOf(current), gatewayNewStageBoundary(listOf(current), emptyList()))
    }
}
