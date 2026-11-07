package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskAcceptanceScopeTest {
    @Test fun standardChatRepositoryWorkRequiresEvidenceWhileConversationDoesNot() {
        for (request in listOf("Test all GitHub tools", "Implement the feature and run the repository tests", "Fix the bug in app.kt")) {
            assertTrue(request, requiresNativeGitHubAcceptance(request, true))
            assertFalse(requiresNativeGitHubAcceptance(request, false))
        }
        for (request in listOf("Hi", "Hello", "test", "test the chat", "Explain what a repository is", "Test my math skills", "Create a file in Google Drive", "List my GitHub repositories", "Find the repositories I can access on GitHub")) {
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
