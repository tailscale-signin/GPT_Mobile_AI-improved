package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPanelVisibilityTest {
    @Test fun githubDestinationHidesInheritedResearchBeforeContextIsBound() {
        val tools = ChatMcpToolConfig(research = MultiSearchResearchSelection(researchToggle = true, depth = 80))
        assertFalse(researchPanelsVisible(false, tools))
        assertTrue(researchPanelsVisible(true, tools))
    }

    @Test fun persistedGithubContextAlsoHidesResearchInOrdinaryChatDestination() {
        val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "profile")
        val tools = ChatMcpToolConfig(github = context, research = MultiSearchResearchSelection(researchToggle = true, depth = 80))
        assertFalse(researchPanelsVisible(true, tools))
    }

    @Test fun ordinaryAndDisabledResearchChatsHideRetainedPanels() {
        assertFalse(researchPanelsVisible(true, ChatMcpToolConfig()))
        assertFalse(researchPanelsVisible(true, ChatMcpToolConfig(research = MultiSearchResearchSelection(researchToggle = false, depth = 80))))
        assertFalse(researchPanelsVisible(true, ChatMcpToolConfig(research = MultiSearchResearchSelection(researchToggle = true, depth = 0))))
    }
}
