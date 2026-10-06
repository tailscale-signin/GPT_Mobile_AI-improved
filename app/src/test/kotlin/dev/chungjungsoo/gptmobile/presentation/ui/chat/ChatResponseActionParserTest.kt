package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseActionParserTest {

    @Test
    fun `combined response keeps distinct suggested questions from every profile`() {
        val response = """
            First model's evidence.
            Next questions:
            - **Archive search:** How can I search archived conversations?

            Second model's evidence.
            Next questions:
            - **Archive search:** How can I search archived conversations?
            - **Backup restore:** How can I restore a backup on another phone?
        """.trimIndent()
        assertEquals(listOf("Archive Search", "Backup Restore"), ChatResponseActionParser.extractDynamicActions(response, false).map { it.label })
    }

    @Test
    fun `generated topic labels keep the complete specific follow-up questions`() {
        val response = """
            Archived conversations are compressed losslessly.

            Next questions:
            - **Archive search:** How can I search the text of compressed archived conversations?
            - **Backup recovery:** How are archived conversations restored from a backup?
        """.trimIndent()
        val actions = ChatResponseActionParser.extractDynamicActions(response, false)
        assertEquals(listOf("Archive Search", "Backup Recovery"), actions.map { it.label })
        assertEquals("How can I search the text of compressed archived conversations?", actions[0].actionPrompt)
        assertEquals("How are archived conversations restored from a backup?", actions[1].actionPrompt)
    }

    @Test
    fun `single direct offer becomes a topic chip instead of disappearing`() {
        val actions = ChatResponseActionParser.extractDynamicActions("Would you like me to explain Room transaction isolation?", false)
        assertEquals(1, actions.size)
        assertTrue(actions.single().label.contains("Isolation"))
        assertEquals("explain Room transaction isolation", actions.single().actionPrompt)
    }

    @Test
    fun `facts code examples and unknown user answers are not offered as actions`() {
        assertTrue(ChatResponseActionParser.extractDynamicActions("Three facts:\n1. Paris is in France\n2. Rome is in Italy", false).isEmpty())
        assertTrue(ChatResponseActionParser.extractDynamicActions("```text\nWould you like me to delete your backup?\n```", false).isEmpty())
        assertTrue(ChatResponseActionParser.extractDynamicActions("Which Android version are you using?", false).isEmpty())
        assertTrue(ChatResponseActionParser.extractDynamicActions("Would you like more details?", false).isEmpty())
    }

    @Test
    fun `different full questions survive and Unicode topic words remain readable`() {
        val response = """
            Next questions:
            - **Résumé export:** How can I export my résumé as plain text?
            - **Résumé backup:** How can I back up my résumé conversation?
        """.trimIndent()
        val actions = ChatResponseActionParser.extractDynamicActions(response, false)
        assertEquals(2, actions.size)
        assertEquals("Résumé Export", actions[0].label)
        assertTrue(actions.all { it.label.length <= 32 && it.actionPrompt.endsWith("?") })
    }

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
    fun `extractDynamicActions extracts numbered list options with clean short labels`() {
        val response = """
            Here are the next steps you can take:
            1. Search online for current documentation
            2. Summarize key advantages and trade-offs
            3. Explain architecture details
        """.trimIndent()

        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)
        assertEquals(3, actions.size)

        assertEquals("Search online for current documentation", actions[0].actionPrompt)
        assertEquals("Search Current Documentation", actions[0].label)
        assertEquals(ActionIconType.SEARCH, actions[0].iconType)

        assertEquals("Summarize key advantages and trade-offs", actions[1].actionPrompt)
        assertEquals(ActionIconType.SUMMARIZE, actions[1].iconType)

        assertEquals("Explain architecture details", actions[2].actionPrompt)
        assertEquals("Explain Architecture", actions[2].label)
        assertEquals(ActionIconType.EXPLAIN, actions[2].iconType)
    }

    @Test
    fun `extractDynamicActions extracts bulleted and bold options`() {
        val response = """
            You have several ways to proceed:
            * **Option A:** Search web for references
            * **Option B:** Summarize the core findings
            * **Option C:** Deep dive into implementation
        """.trimIndent()

        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)
        assertEquals(3, actions.size)
        assertEquals("Search Web References", actions[0].label)
        assertEquals("Search web for references", actions[0].actionPrompt)
        assertEquals("Summarize Core Findings", actions[1].label)
        assertEquals("Deep Dive Implementation", actions[2].label)
    }

    @Test
    fun `extractDynamicActions extracts binary question choices`() {
        val response = "Would you like me to apply these changes? (Yes or No?)"
        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)
        assertEquals(2, actions.size)
        assertEquals("Apply Changes", actions[0].label)
        assertEquals(ActionIconType.CONFIRM, actions[0].iconType)
        assertEquals("No thanks", actions[1].label)
        assertEquals(ActionIconType.CANCEL, actions[1].iconType)
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
        assertEquals("Look Official Benchmarks", actions[0].label)
        assertEquals(ActionIconType.SEARCH, actions[0].iconType)
        assertEquals("Provide practical examples", actions[1].actionPrompt)
        assertEquals("Practical Examples", actions[1].label)
    }

    @Test
    fun `extractDynamicActions extracts inline question alternatives`() {
        val response = "Would you like me to search online, summarize this, or provide code examples?"
        val actions = ChatResponseActionParser.extractDynamicActions(response, isLoading = false)

        assertTrue(actions.size in 2..4)
        assertEquals("Search Online", actions[0].label)
        assertEquals(ActionIconType.SEARCH, actions[0].iconType)
        assertEquals("Summarize", actions[1].label)
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
