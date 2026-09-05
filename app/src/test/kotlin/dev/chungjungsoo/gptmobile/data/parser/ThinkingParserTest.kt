package dev.chungjungsoo.gptmobile.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingParserTest {

    @Test
    fun `parseThinking with standard think tags extracts thinking and clean content`() {
        val input = "<think>Analyzing user request...</think>Hello, how can I help you today?"
        val (thinking, content) = ThinkingParser.parseThinking(input)

        assertEquals("Analyzing user request...", thinking)
        assertEquals("Hello, how can I help you today?", content)
    }

    @Test
    fun `parseThinking without think tags returns null thinking and unchanged content`() {
        val input = "Hello, world!"
        val (thinking, content) = ThinkingParser.parseThinking(input)

        assertNull(thinking)
        assertEquals("Hello, world!", content)
    }

    @Test
    fun `parseThinking with empty think tags returns null thinking and clean content`() {
        val input = "<think></think>Actual response."
        val (thinking, content) = ThinkingParser.parseThinking(input)

        assertNull(thinking)
        assertEquals("Actual response.", content)
    }

    @Test
    fun `parseThinking with whitespace in think tags returns null thinking`() {
        val input = "<think>   \n\t  </think>Actual response."
        val (thinking, content) = ThinkingParser.parseThinking(input)

        assertNull(thinking)
        assertEquals("Actual response.", content)
    }

    @Test
    fun `parseThinking with multi-line think tags preserves thinking lines trimmed`() {
        val input = """
            <think>
            Step 1: Check memory
            Step 2: Calculate result
            </think>
            The final answer is 42.
        """.trimIndent()

        val (thinking, content) = ThinkingParser.parseThinking(input)

        val expectedThinking = """
            Step 1: Check memory
            Step 2: Calculate result
        """.trimIndent()

        assertEquals(expectedThinking, thinking)
        assertEquals("The final answer is 42.", content)
    }

    @Test
    fun `parseThinking handles case-insensitivity in think tags`() {
        val input = "<THINK>Capitalized reasoning</THINK>Output response"
        val (thinking, content) = ThinkingParser.parseThinking(input)

        assertEquals("Capitalized reasoning", thinking)
        assertEquals("Output response", content)
    }
}
