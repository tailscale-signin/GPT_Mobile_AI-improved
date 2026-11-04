package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequestedBehaviorTest {
    @Test fun gatewayIntegrationDoesNotBecomeGithubInventoryTesting() {
        org.junit.Assert.assertTrue(isGatewayIntegrationTask("Do a test of the gateway Integration"))
        org.junit.Assert.assertFalse(GitHubTestChecklist.integration("Do a test of the gateway Integration"))
        org.junit.Assert.assertFalse(isGatewayIntegrationTask("Implement the gateway fixes and test them"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun regexCannotSilentlyReturnZeroGlobMatches() {
        GitHubSearchOptions.from(kotlinx.serialization.json.Json.parseToJsonElement("""{"mode":"patterns","query":"class.*Test"}""") as kotlinx.serialization.json.JsonObject)
    }

    @Test fun pathWildcardsWorkWithoutChangingLiteralSourceSearch() {
        val options = GitHubSearchOptions.from(
            buildJsonObject {
                put("mode", "paths")
                put("query", "*GitHub*.kt")
            }
        )
        assertEquals(1.0, options.score("app/src/main/kotlin/GitHubChat.kt", "*GitHub*.kt"), 0.0)
        assertEquals(0.0, options.score("app/src/main/kotlin/VoiceChat.kt", "*GitHub*.kt"), 0.0)
        val literal = GitHubSearchOptions.from(buildJsonObject { put("mode", "literal") })
        assertEquals(0.0, literal.score("file.kt", "*.kt"), 0.0)
        assertEquals(
            1.0,
            GitHubSearchOptions.from(
                buildJsonObject {
                    put("mode", "paths")
                    put("query", "*")
                }
            ).score("file.kt", "*"),
            0.0
        )
    }

    @Test fun reviewAcceptanceDoesNotRequireImplementationButFixDoes() {
        assertTrue(GitHubRequestCompletion.informational("Please analyze all these logs, list errors, and offer solutions."))
        assertTrue(GitHubRequestCompletion.informational("I want you to review this repository"))
        assertFalse(GitHubRequestCompletion.informational("Implement the fixes and run the tests"))
    }

    @Test fun terseFollowUpRetainsTheScopedTask() {
        val previous = "Analyze the repository's network failures"
        assertTrue(GitHubTaskContinuity.reuse("fix", previous))
        assertTrue(GitHubTaskContinuity.resolved("fix", previous).contains(previous))
        assertTrue(GitHubTaskContinuity.resolved("fix", previous).startsWith("Implement"))
        assertFalse(GitHubTaskContinuity.reuse("yes", ""))
    }

    @Test fun workflowsProvideFiveDistinctCompletePromptsIncludingIntegration() {
        assertEquals(5, GitHubQuickWorkflow.defaults.size)
        assertEquals(5, GitHubQuickWorkflow.defaults.map { it.id }.toSet().size)
        assertTrue(GitHubQuickWorkflow.defaults.all { it.title.isNotBlank() && it.prompt.length > 100 })
        assertTrue(GitHubQuickWorkflow.defaults.any { it.prompt.contains("[github-workflow-preset:test_integration]") })
    }
}
