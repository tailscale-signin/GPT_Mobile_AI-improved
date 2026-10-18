package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlockType
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentStartResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageStopResponseChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicCompleteResponseTest {
    @Test fun normalizesTextThinkingAndToolUseWithoutDroppingSignatures() {
        val chunks = anthropicCompleteResponseChunks("""{"id":"m1","model":"test","role":"assistant","content":[{"type":"thinking","thinking":"reason","signature":"sig"},{"type":"text","text":"Answer"},{"type":"tool_use","id":"call1","name":"weather","input":{"city":"Toronto"}}],"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":8}}""")
        val blocks = chunks.filterIsInstance<ContentStartResponseChunk>().map { it.contentBlock }
        assertEquals("sig", blocks[0].signature)
        assertEquals("Answer", blocks[1].text)
        assertEquals(ContentBlockType.TOOL_USE, blocks[2].type)
        assertEquals("weather", blocks[2].name)
        assertEquals(MessageStopResponseChunk, chunks.last())
    }

    @Test fun missingTerminalReceiptIsRejected() {
        assertTrue(runCatching { anthropicCompleteResponseChunks("""{"id":"m1","model":"test","content":[],"usage":{"input_tokens":1,"output_tokens":1}}""") }.isFailure)
    }
}
