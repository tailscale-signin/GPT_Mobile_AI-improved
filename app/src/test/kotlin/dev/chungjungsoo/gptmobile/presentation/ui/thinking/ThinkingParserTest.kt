package dev.chungjungsoo.gptmobile.presentation.ui.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {

    @Test
    fun parseExtractsThinkBlockAndRemainingContent() {
        val input = "<think>Let me calculate 2+2.</think>The answer is 4."
        val result = ThinkingParser.parse(input)
        assertEquals("Let me calculate 2+2.", result.thinkingText)
        assertEquals("The answer is 4.", result.mainContent)
        assertFalse(result.isStillThinking)
    }

    @Test
    fun parseHandlesInputWithoutThinkBlock() {
        val input = "Just regular response."
        val result = ThinkingParser.parse(input)
        assertNull(result.thinkingText)
        assertEquals("Just regular response.", result.mainContent)
        assertFalse(result.isStillThinking)
    }

    @Test
    fun parseHandlesEmptyThinkBlock() {
        val input = "<think></think>No thoughts."
        val result = ThinkingParser.parse(input)
        assertNull(result.thinkingText)
        assertEquals("No thoughts.", result.mainContent)
        assertFalse(result.isStillThinking)
    }

    @Test
    fun parseHandlesMultilineThinking() {
        val input = """
            <think>
            Step 1: Check inputs.
            Step 2: Compute result.
            </think>
            Done.
        """.trimIndent()
        val result = ThinkingParser.parse(input)
        assertEquals("Step 1: Check inputs.\nStep 2: Compute result.", result.thinkingText)
        assertEquals("Done.", result.mainContent)
        assertFalse(result.isStillThinking)
    }

    @Test
    fun parseHandlesUnclosedThinkTagWhileStreaming() {
        val input = "<think>I am currently thinking about quantum physics..."
        val result = ThinkingParser.parse(input)
        assertEquals("I am currently thinking about quantum physics...", result.thinkingText)
        assertEquals("", result.mainContent)
        assertTrue(result.isStillThinking)
    }
}
