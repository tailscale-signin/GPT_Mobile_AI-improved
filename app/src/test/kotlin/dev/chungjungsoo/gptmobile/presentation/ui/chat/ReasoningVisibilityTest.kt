package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningVisibilityTest {
    private val thoughts = AssistantTimelineItem(AssistantTimelineItemType.THINKING, "reasoning")
    private val embedded = AssistantTimelineItem(AssistantTimelineItemType.TEXT, "<think>reasoning</think>Answer")
    private val tool = AssistantTimelineItem(AssistantTimelineItemType.TOOL)

    @Test
    fun thinkingIsVisibleByDefaultWithoutDebugMode() {
        assertTrue(AppFeatureSettings().showReasoning)
        assertEquals(listOf(thoughts, embedded), processTimelineForDisplay(listOf(thoughts, embedded, tool), true, false))
    }

    @Test
    fun advancedSwitchHidesExplicitAndEmbeddedThinkingEvenInDebugMode() {
        assertFalse(AppFeatureSettings().withFeature(AppFeature.SHOW_REASONING, false).showReasoning)
        assertEquals(listOf(tool), processTimelineForDisplay(listOf(thoughts, embedded, tool), false, true))
        assertTrue(processTimelineForDisplay(listOf(thoughts, embedded, tool), false, false).isEmpty())
    }

    @Test
    fun toolLabelsCoverNativeAndMarketplaceTools() {
        assertEquals("Finding location", friendlyToolActivity("device_location"))
        assertEquals("Searching the web", friendlyToolActivity("web_search"))
        assertEquals("Custom report", friendlyToolActivity("server__custom_report"))
    }
}
