package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.presentation.ui.chat.ActionIconType
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatResponseActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseActionParserTest {

    @Test
    fun `shouldShowContinuePrompt detects varied continue prompts`() {
        assertFalse(ChatResponseActionParser.shouldShowContinuePrompt("", isLoading = false))
        assertFalse(ChatResponseActionParser.shouldShowContinuePrompt("Some random answer.", isLoading = false))
        assertFalse(ChatResponseActionParser.shouldShowContinuePrompt("Would you like to continue?", isLoading = true))

        // Direct phrase matching
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Here is part 1. Would you like me to continue?", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Should I continue with the explanation?", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Shall I continue?", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Do you want me to keep going?", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Let me know if you want me to continue.", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Reply with 'continue' to proceed.", isLoading = false))

        // Truncated / ellipsis
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Here is step 1 and...", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("```kotlin\nval x = 1", isLoading = false)) // unclosed code block

        // Natural questions asking to proceed or continue
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Would you like more details?", isLoading = false))
        assertTrue(ChatResponseActionParser.shouldShowContinuePrompt("Should I proceed?", isLoading = false))
    }

    @Test
    fun `extractDynamicActions extracts numbered list options`() {
        val response = """
            Here are the next steps you can take:
            1. Search online for current documentation
            2. Summarize key advantages and trade-offs
            3. Explain architecture details
        """.trimIndent()

        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)
        assertEquals(3, actions.size)

        assertEquals("Search online for current documentation", actions[0].actionPrompt)
        assertEquals(ActionIconType.SEARCH, actions[0].iconType)

        assertEquals("Summarize key advantages and trade-offs", actions[1].actionPrompt)
        assertEquals(ActionIconType.SUMMARIZE, actions[1].iconType)

        assertEquals("Explain architecture details", actions[2].actionPrompt)
        assertEquals(ActionIconType.EXPLAIN, actions[2].iconType)
    }

    @Test
    fun `extractDynamicActions extracts lettered list options`() {
        val response = """
            What would you like to explore next?
            A) Look up official benchmarks
            B) Provide practical examples
        """.trimIndent()

        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)
        assertEquals(2, actions.size)
        assertEquals("Look up official benchmarks", actions[0].actionPrompt)
        assertEquals(ActionIconType.SEARCH, actions[0].iconType)
        assertEquals("Provide practical examples", actions[1].actionPrompt)
    }

    @Test
    fun `extractDynamicActions extracts inline question alternatives`() {
        val response = "Would you like me to search online, summarize this, or provide code examples?"
        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)

        assertTrue(actions.size in 2..4)
        assertEquals("Search online", actions[0].label)
        assertEquals(ActionIconType.SEARCH, actions[0].iconType)
        assertEquals("Summarize this", actions[1].label)
        assertEquals(ActionIconType.SUMMARIZE, actions[1].iconType)
    }

    @Test
    fun `extractDynamicActions returns empty when loading or no options`() {
        val response = "The capital of France is Paris."
        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)
        assertTrue(actions.isEmpty())

        val loadingActions = ChatResponseActionParser.extractDynamicActions("1. Option A\n2. Option B", isLoading = true)
        assertTrue(loadingActions.isEmpty())
    }
}
