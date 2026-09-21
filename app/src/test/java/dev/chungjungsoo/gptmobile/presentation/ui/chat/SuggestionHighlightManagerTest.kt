package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionHighlightManagerTest {

    @Test
    fun splitIntoSentences_extractsCleanSentences() {
        val response = "Here is an explanation of the topic. Would you like me to continue with details? Please let me know."
        val sentences = SuggestionHighlightManager.splitIntoSentences(response)

        assertTrue(sentences.isNotEmpty())
        assertEquals(3, sentences.size)
        assertEquals("Here is an explanation of the topic.", sentences[0])
        assertEquals("Would you like me to continue with details?", sentences[1])
        assertEquals("Please let me know.", sentences[2])
    }

    @Test
    fun findMatchingSentence_matchesCorrectSentenceConfidentially() {
        val response = "We can optimize the database performance now. In addition, we can search online for documentation. Finally, you can export the results."
        val match = SuggestionHighlightManager.findMatchingSentence(
            responseContent = response,
            buttonText = "Search Online",
            promptText = "search online for documentation"
        )

        assertNotNull(match)
        assertTrue(match!!.contains("search online for documentation", ignoreCase = true))
    }

    @Test
    fun findMatchingSentence_returnsNullWhenNoMatch() {
        val response = "The task has completed successfully. No further actions needed."
        val match = SuggestionHighlightManager.findMatchingSentence(
            responseContent = response,
            buttonText = "Deploy Kubernetes Cluster",
            promptText = "deploy cluster to production"
        )

        assertNull(match)
    }

    @Test
    fun findMatchingSentence_tracksFinalSettledText_whenStreamingSettles() {
        val streamedPartial = "We can proceed with option"
        val streamedSettled = "We can proceed with option A or option B. Would you like to continue?"

        val match1 = SuggestionHighlightManager.findMatchingSentence(
            responseContent = streamedPartial,
            buttonText = "Continue",
            promptText = "continue"
        )
        assertNull(match1)

        val match2 = SuggestionHighlightManager.findMatchingSentence(
            responseContent = streamedSettled,
            buttonText = "Continue",
            promptText = "continue"
        )
        assertNotNull(match2)
        assertTrue(match2!!.contains("Would you like to continue?", ignoreCase = true))
    }

    @Test
    fun interpolateFontWeight_transitionsFromNormalToBold() {
        val weight0 = SuggestionHighlightManager.interpolateFontWeight(0f)
        val weightHalf = SuggestionHighlightManager.interpolateFontWeight(0.5f)
        val weight1 = SuggestionHighlightManager.interpolateFontWeight(1f)

        assertEquals(400, weight0.weight)
        assertEquals(550, weightHalf.weight)
        assertEquals(700, weight1.weight)
    }
}
