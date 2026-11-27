package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceIntentTest {
    @Test fun selectedWorkspaceDoesNotTurnGeneralQuestionsIntoRepositoryTasks() {
        for (request in listOf("Hello", "What is photosynthesis?", "Explain why CI pipelines are useful", "Search the web for Android indexing libraries", "Research the latest GitHub pricing", "Create a file in Google Drive", "What tools can you use in this chat?")) {
            assertFalse(request, IntelligenceIntent.from(request, selectedRepository = true).repository)
        }
        assertTrue(IntelligenceIntent.from("Search the web for Android indexing libraries", true).research)
    }

    @Test fun scopedQuestionsAndAcceptedImplementationFollowupsRetainRepositoryContext() {
        for (request in listOf("List open issues", "Show PR #12", "Inspect commits", "How does our app handle tool caching?", "Where is the choice prompt implemented?", "Implement the first three improvements", "Apply those improvements to our latest branch", "continue")) {
            assertTrue(request, IntelligenceIntent.from(request, selectedRepository = true).repository)
        }
        assertFalse(IntelligenceIntent.from("Implement the first three improvements", selectedRepository = false).repository)
    }

    @Test fun mixedResearchAndEngineeringKeepsBothCapabilities() {
        val intent = IntelligenceIntent.from("Research incremental indexing and implement the improvement in our repository", true)
        assertTrue(intent.repository)
        assertTrue(intent.research)
    }
}
