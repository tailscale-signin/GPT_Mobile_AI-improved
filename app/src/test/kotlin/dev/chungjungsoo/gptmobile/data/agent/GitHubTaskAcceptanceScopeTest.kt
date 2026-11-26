package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel
import dev.chungjungsoo.gptmobile.data.github.GitHubPlanningContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskAcceptanceScopeTest {
    private val repository = GitHubRepositoryContext("owner", "project", "main")
    private val target = GitHubPlanningContext("connection", "", repository, "a".repeat(40))
    private val workspace = GitHubConversationContext("connection", "owner", "project", "main", "a".repeat(40), "profile")

    @Test fun selectedPluginAndDedicatedChatUseTheSameRequestPolicy() {
        val saved = GitHubChatProfileOverride(intelligenceLevel = GitHubIntelligenceLevel.DEEP_ENGINEERING)
        for (request in listOf("List open issues", "Show PR #12", "Fix the bug in app.kt")) {
            val dedicated = GitHubTaskScope.select(request, workspace, listOf(target), true)
            val plugin = GitHubTaskScope.select(request, null, listOf(target, target), true)
            assertEquals(dedicated, plugin)
            assertTrue(plugin.requiresAcceptance)
            assertTrue(plugin.usesRepositoryIntelligence)
            assertEquals(GitHubChatProfileOverride.newProfile, plugin.planningPreferences(null))
            assertEquals(saved, plugin.planningPreferences(saved))
        }
    }

    @Test fun anUnrelatedRequestOrUnboundPluginCannotSelectRepositoryIntelligence() {
        for (request in listOf("Hello", "List my GitHub repositories", "What GitHub tools do you have?")) {
            val scope = GitHubTaskScope.select(request, null, listOf(target), true)
            assertFalse(request, scope.usesRepositoryIntelligence)
            assertFalse(request, scope.requiresAcceptance)
            assertNull(scope.planningPreferences(null))
        }
        assertFalse(GitHubTaskScope.select("List open issues", null, emptyList(), false).requiresAcceptance)
        val ambiguous = GitHubTaskScope.select("Show PR #12", null, listOf(target, target.copy(connectionUid = "other")), true)
        assertTrue(ambiguous.requiresAcceptance)
        assertFalse(ambiguous.usesRepositoryIntelligence)
        assertTrue(runCatching { requireNativeGitHubTarget(ambiguous.requiresAcceptance, ambiguous.targetCount) }.isFailure)
    }

    @Test fun differentObservationsOfOneTargetKeepItsBindingAndLeaveTheHeadUnresolved() {
        val scope = GitHubTaskScope.select("Show PR #12", null, listOf(target, target.copy(observedCommitSha = "b".repeat(40))), true)
        assertEquals(repository, scope.repository)
        assertEquals("connection", scope.connectionUid)
        assertNull(scope.observedCommitSha)
    }

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
        for (count in listOf(0, 2)) {
            val error = requireNotNull(runCatching { requireNativeGitHubTarget(true, count) }.exceptionOrNull())
            val rejection = requireNotNull(AgentFailureEvidence.exception(error))
            assertTrue(rejection.errorCode == "REPOSITORY_TARGET_UNVERIFIED" && rejection.taskOutcome == "BLOCKED")
            assertTrue(rejection.failureDetails?.get("dispatched") == kotlinx.serialization.json.JsonPrimitive(false))
        }
        requireNativeGitHubTarget(true, 1)
        requireNativeGitHubTarget(false, 0)
        requireNativeGitHubTarget(false, 2)
        assertTrue(runCatching { requireNativeGitHubTarget(true, 0) }.exceptionOrNull()?.message.orEmpty().startsWith("GITHUB_REPOSITORY_TARGET_REQUIRED"))
        assertTrue(runCatching { requireNativeGitHubTarget(true, 2) }.exceptionOrNull()?.message.orEmpty().startsWith("GITHUB_REPOSITORY_TARGET_AMBIGUOUS"))
    }

    @Test fun repositoryLifecycleWorkDoesNotRequireTheDestinationToAlreadyExist() {
        for (request in listOf("Create a private repository", "Fork the source repository", "Fork upstream/project", "Import a project into a new repository", "Clone upstream/project to the gateway")) {
            assertTrue(request, requiresNativeGitHubAcceptance(request, true))
            requireNativeGitHubTarget(true, 0, request)
        }
        assertFalse(requiresNativeGitHubAcceptance("Fork upstream/project", false))
        assertTrue(runCatching { requireNativeGitHubTarget(true, 0, "Do not create a repository. Inspect the selected repository.") }.isFailure)
        assertTrue(runCatching { requireNativeGitHubTarget(true, 0, "Read app.kt from the repository") }.isFailure)
    }
}
