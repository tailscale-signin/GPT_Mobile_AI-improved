package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProfileAuditSuiteTest {
    @Test
    fun quickSuiteUsesExactlyOneCasePerCurrentGitHubTool() {
        val quick = GitHubProfileAuditSuite.cases(allActions = false)
        assertEquals(GitHubOperationTool.names.size, quick.size)
        assertEquals(GitHubOperationTool.names, quick.mapNotNull { GitHubProfileAuditSuite.fixture(it)?.operation }.toSet())
        assertTrue(quick.all { it.category == "tools" && it.workload == GitHubProfileAuditSuite.WORKLOAD })
    }

    @Test
    fun fullSuiteCoversEveryAdvertisedActionWithUniqueAndValidArguments() {
        val cases = GitHubProfileAuditSuite.cases(allActions = true)
        assertEquals(GitHubProfileAuditSuite.actionCount(), cases.size)
        assertEquals(cases.size, cases.map { it.id }.distinct().size)
        assertTrue(cases.size >= GitHubProfileAuditSuite.familyCount())
        for (test in cases) {
            val fixture = GitHubProfileAuditSuite.fixture(test)
            assertNotNull("Missing scenario for " + test.id, fixture)
            val accepted = requireNotNull(fixture)
            assertTrue(
                "Invalid GitHub tool schema fixture " + test.id,
                ToolArgumentValidator.errors(accepted.arguments, accepted.definition.inputSchema).isEmpty()
            )
            assertTrue(
                "Invalid GitHub operation admission fixture " + test.id,
                GitHubOperationArguments.errors(accepted.operation.removeSuffix("_inspect"), accepted.arguments).isEmpty()
            )
        }
    }

    @Test
    fun copilotUpdateFixtureKeepsExplicitMinimalRepairPermission() {
        val test = GitHubProfileAuditSuite.cases(allActions = true)
            .single { it.id == "github-ai-v1:github_pr:agent_merge_update" }
        val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(test))
        assertEquals(JsonArray(listOf(JsonPrimitive("fix_ci"))), fixture.arguments["allowed_actions"])
        assertTrue(GitHubOperationArguments.errors("github_pr", fixture.arguments).isEmpty())
    }

    @Test
    fun offlineWriteFixtureUsesOnlyAnInMemoryTool() = runBlocking {
        val test = GitHubProfileAuditSuite.cases(allActions = true).single { it.id == "github-ai-v1:github_write:commit" }
        val fixture = requireNotNull(GitHubProfileAuditSuite.fixture(test))
        val scenario = BenchmarkToolScenario(test, "OFFLINE-FIXTURE-OK")
        val tool = scenario.tools.single()
        assertEquals("github_write", tool.definition.name)
        val call = ProviderEvent.ToolCall("fixture-call-1", fixture.operation, fixture.arguments)
        assertTrue(scenario.valid(call))
        val result = tool.execute(call.callId, call.arguments)
        assertFalse(result.isError)
        assertTrue(scenario.passed(listOf(call), setOf(call.callId), emptySet()))
        assertFalse(scenario.valid(call.copy(arguments = kotlinx.serialization.json.buildJsonObject {})))
    }
}
