package dev.chungjungsoo.gptmobile.ui.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {

    @Test
    fun testParseNoThinking() {
        val input = "Hello, how can I help you today?"
        val result = ThinkingParser.parse(input)
        assertNull(result.thinking)
        assertEquals("Hello, how can I help you today?", result.content)
        assertFalse(result.isThinking)
    }

    @Test
    fun testParseCompleteThinking() {
        val input = "<think>\nLet me analyze this user request.\nStep 1: Check greeting.\n</think>\nHello! How can I assist you?"
        val result = ThinkingParser.parse(input)
        assertEquals("Let me analyze this user request.\nStep 1: Check greeting.", result.thinking)
        assertEquals("Hello! How can I assist you?", result.content)
        assertFalse(result.isThinking)
    }

    @Test
    fun testParseStreamingOpenThinking() {
        val input = "<think>\nCurrently contemplating the universe and calculating..."
        val result = ThinkingParser.parse(input)
        assertEquals("Currently contemplating the universe and calculating...", result.thinking)
        assertEquals("", result.content)
        assertTrue(result.isThinking)
    }

    @Test
    fun testParseEmptyThinking() {
        val input = "<think></think>Direct answer here."
        val result = ThinkingParser.parse(input)
        assertNull(result.thinking)
        assertEquals("Direct answer here.", result.content)
        assertFalse(result.isThinking)
    }
}
