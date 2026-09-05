package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {

    @Test
    fun parse_withCompletedThinkingBlock_returnsThinkingAndAnswer() {
        val raw = "<think>Analyzing user request step by step.</think>Here is the final response."
        val result = ThinkingParser.parse(raw)

        assertEquals("Analyzing user request step by step.", result.thinking)
        assertEquals("Here is the final response.", result.displayContent)
        assertTrue(result.hasThinking)
        assertFalse(result.isThinkingInProgress)
    }

    @Test
    fun parse_withUnclosedThinkingBlock_marksThinkingInProgress() {
        val raw = "<think>I am currently pondering the solution"
        val result = ThinkingParser.parse(raw)

        assertEquals("I am currently pondering the solution", result.thinking)
        assertEquals("", result.displayContent)
        assertTrue(result.hasThinking)
        assertTrue(result.isThinkingInProgress)
    }

    @Test
    fun parse_withoutThinkingTag_returnsOriginalContent() {
        val raw = "Hello, how can I help you today?"
        val result = ThinkingParser.parse(raw)

        assertNull(result.thinking)
        assertEquals("Hello, how can I help you today?", result.displayContent)
        assertFalse(result.hasThinking)
        assertFalse(result.isThinkingInProgress)
    }

    @Test
    fun parse_withMultipleThinkingBlocks_extractsFirstAndPreservesAnswer() {
        val raw = "<think>First thought</think>Intermediate <think>Second thought</think>Final answer"
        val result = ThinkingParser.parse(raw)

        assertTrue(result.hasThinking)
        assertEquals("First thought", result.thinking)
        assertEquals("Intermediate <think>Second thought</think>Final answer", result.displayContent)
    }

    @Test
    fun parse_withEmptyThinkingBlock_returnsEmptyThinking() {
        val raw = "<think></think>Actual answer"
        val result = ThinkingParser.parse(raw)

        assertEquals("", result.thinking)
        assertEquals("Actual answer", result.displayContent)
        assertTrue(result.hasThinking)
        assertFalse(result.isThinkingInProgress)
    }
}
