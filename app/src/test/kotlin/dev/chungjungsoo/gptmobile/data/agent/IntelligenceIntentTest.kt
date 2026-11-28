package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceIntentTest {
    @Test fun pureAccountDiscoveryUsesTheSameScopeAsAcceptanceAndCompoundWorkRetainsItsTarget() {
        for (request in listOf("List my GitHub repositories", "Please show me all my repositories.", "Find the repositories I can access on GitHub")) {
            assertTrue(request, IntelligenceIntent.accountRepositoryDiscovery(request))
            assertFalse(request, requiresNativeGitHubAcceptance(request, true))
            assertFalse(request, IntelligenceIntent.from(request, true).repository)
        }
        val compound = "List my GitHub repositories and inspect the selected repository"
        assertFalse(IntelligenceIntent.accountRepositoryDiscovery(compound))
        assertTrue(requiresNativeGitHubAcceptance(compound, true))
        assertTrue(IntelligenceIntent.from(compound, true).repository)
    }

    @Test fun selectedWorkspaceDoesNotTurnGeneralQuestionsIntoRepositoryTasks() {
        for (request in listOf("Hello", "What is photosynthesis?", "Explain why CI pipelines are useful", "Search the web for Android indexing libraries", "Research the latest GitHub pricing", "Review GitHub pricing", "Show GitHub pricing", "Read source materials about climate", "Create a file in Google Drive", "Review Google Drive files", "Create hello.py in Google Drive", "Apply those changes in Google Drive", "Update the file in Dropbox", "What tools can you use in this chat?", "List my GitHub repositories", "Please show me all my repositories.", "Show available GitHub repos", "List accessible repositories", "Find the repositories I can access on GitHub")) {
            assertFalse(request, IntelligenceIntent.from(request, selectedRepository = true).repository)
            assertFalse(request, IntelligenceIntent.from(request, selectedRepository = false).repository)
        }
        assertTrue(IntelligenceIntent.from("Search the web for Android indexing libraries", true).research)
        assertFalse(requiresNativeGitHubAcceptance("Create hello.py in Google Drive", true))
        assertTrue(requiresNativeGitHubAcceptance("Fix the Google Drive integration in this repository", true))
        assertTrue(requiresNativeGitHubAcceptance("Update hello.py on GitHub and copy it to Google Drive", true))
    }

    @Test fun scopedQuestionsAndAcceptedImplementationFollowupsRetainRepositoryContext() {
        for (request in listOf("List open issues", "Show PR #12", "Inspect commits", "status", "repo status", "Inspect git", "Read source", "How does our app handle tool caching?", "Where is the choice prompt implemented?", "Implement the first three improvements", "Implement number four", "Apply number 4", "Apply those improvements to our latest branch", "Fix the Google Drive integration in this repository", "Review PR #12 and save the summary in Google Drive", "Update hello.py on GitHub and copy it to Google Drive", "continue")) {
            assertTrue(request, IntelligenceIntent.from(request, selectedRepository = true).repository)
        }
        assertFalse(IntelligenceIntent.from("Implement the first three improvements", selectedRepository = false).repository)
        assertFalse(IntelligenceIntent.from("status", selectedRepository = false).repository)
        assertTrue(IntelligenceIntent.from("Check repo status", selectedRepository = false).repository)
        assertTrue(IntelligenceIntent.from("Read source", selectedRepository = false).repository)
    }

    @Test fun mixedResearchAndEngineeringKeepsBothCapabilities() {
        val intent = IntelligenceIntent.from("Research incremental indexing and implement the improvement in our repository", true)
        assertTrue(intent.repository)
        assertTrue(intent.research)
    }

    @Test fun conciseRepositoryReadsAndDiscoveryQueriesDoNotNeedASelectedWorkspace() {
        for (request in listOf("Check repo status", "repo status", "GitHub repository status", "Inspect git", "Show branches", "Inspect commits", "Review repositories", "Read source")) {
            assertTrue(request, IntelligenceIntent.from(request, selectedRepository = false).repository)
        }
        assertTrue(IntelligenceIntent.from("status", selectedRepository = true).repository)
        assertFalse(IntelligenceIntent.from("status", selectedRepository = false).repository)
        assertTrue(IntelligenceIntent.from("Create a file in Google Drive and commit its reference in our repository", selectedRepository = true).repository)
    }
}
