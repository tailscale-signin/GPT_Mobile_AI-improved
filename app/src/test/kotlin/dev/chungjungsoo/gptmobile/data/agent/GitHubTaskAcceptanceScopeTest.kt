package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskAcceptanceScopeTest {
    @Test fun standardChatRepositoryWorkRequiresEvidenceWhileConversationDoesNot() {
        for (request in listOf("Test all GitHub tools", "Implement the feature and run the repository tests", "Fix the bug in app.kt", "Inspect the repository", "Show workflows for the GitHub repository", "Read app.kt")) {
            assertTrue(request, requiresNativeGitHubAcceptance(request, true))
        }
        assertTrue(requiresNativeGitHubAcceptance("Test all GitHub tools", false))
        assertTrue(requiresNativeGitHubAcceptance("Call github_context__github_api with action=status", false))
        assertTrue(requiresNativeGitHubAcceptance("Use github_ci__github_api to inspect jobs", true))
        assertFalse(requiresNativeGitHubAcceptance("Fix the bug in app.kt", false))
        for (request in listOf("Hi", "Hello", "test", "test the chat", "Explain what a repository is", "Show me how a repository works", "Show GitHub pricing", "Test my math skills", "Create a file in Google Drive", "List my GitHub repositories", "Find the repositories I can access on GitHub")) {
            assertFalse(request, requiresNativeGitHubAcceptance(request, true))
        }
    }

    @Test fun repositoryWorkRequiresOneBoundTargetBeforeAcceptanceCanBeExpected() {
        requireNativeGitHubTarget(true, 1)
        requireNativeGitHubTarget(false, 0)
        requireNativeGitHubTarget(false, 2)
        assertTrue(runCatching { requireNativeGitHubTarget(true, 0) }.exceptionOrNull()?.message.orEmpty().startsWith("GITHUB_REPOSITORY_TARGET_REQUIRED"))
        assertTrue(runCatching { requireNativeGitHubTarget(true, 2) }.exceptionOrNull()?.message.orEmpty().startsWith("GITHUB_REPOSITORY_TARGET_AMBIGUOUS"))
    }
}
