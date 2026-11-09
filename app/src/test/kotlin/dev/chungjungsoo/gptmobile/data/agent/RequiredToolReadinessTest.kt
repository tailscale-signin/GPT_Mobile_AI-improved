package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredToolReadinessTest {
    private fun resolved(name: String, description: String = "Authorized fixture", native: Boolean = false, authenticated: Boolean = false): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(name, description, buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Readiness must not execute tools")
        }
        return ResolvedAgentTool(tool, null, null, name, name, nativeGitHub = native, nativeGitHubAuthenticated = authenticated)
    }
    private fun plan(task: String, tools: List<ResolvedAgentTool>, requestedTask: String? = null, requireToolExecution: Boolean = true) = planAuthorizedToolContext(
        listOf(ConversationTurn(MessageV2(content = task, platformType = null), null, true)),
        "Use the selected tools.",
        tools,
        TokenBudgetSettings(contextTokens = 4096, outputTokens = 1024),
        true,
        requestedTask = requestedTask,
        requireToolExecution = requireToolExecution
    )

    @Test fun anExplicitRequiredToolMustExistBeforeGeneration() {
        val failure = runCatching { plan("Call github_pr for this task.", listOf(resolved("current_date"))) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("REQUIRED_TOOL_CAPABILITY_UNAVAILABLE"))
    }

    @Test fun requiredWriteAuthenticationIsCheckedBeforeGeneration() {
        val failure = runCatching { plan("Use github_commit for this task.", listOf(resolved("github_commit", native = true))) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("REQUIRED_TOOL_AUTHORIZATION_REQUIRED"))
    }

    @Test fun requiredSchemaCannotBeSilentlyDroppedForBudget() {
        val failure = runCatching { plan("Call github_pr.", listOf(resolved("github_pr", "Detailed contract ".repeat(4000)))) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("REQUIRED_TOOL_CONTEXT_BUDGET"))
    }

    @Test fun anExplicitOperationIsAttachedEvenWhenNormallyDeferredByTheCatalog() {
        val commit = resolved("github_commit", native = true, authenticated = true)
        val catalog = ProgressiveToolCatalog(listOf(commit.tool.definition), "Use github_commit.", initialNames = emptySet())
        val directory = ResolvedAgentTool(catalog, null, null, catalog.definition.name, catalog.definition.name, progressiveCatalog = catalog)
        val planned = plan("Use github_commit.", listOf(commit, directory))
        assertTrue(planned.tools.any { it.name == "github_commit" })
    }

    @Test fun retainedReceiptsDoNotBecomeNewUserToolRequirements() {
        val requested = "Continue the integration test from its saved checkpoint."
        val context = "$requested\n\nSaved evidence: github_context__github_api completed; call github_commit__old_connection for the previous task."
        val result = plan(context, listOf(resolved("read_recovery_context")), requestedTask = requested)
        assertEquals(context, result.turns.single().userMessage.content)
        assertEquals(listOf("read_recovery_context"), result.tools.map { it.name })
    }

    @Test fun evidenceOnlyReportingDoesNotReopenCompletedOperationAdmission() {
        val request = "Call github_context__github_api."
        val result = plan(
            "$request\n\nSaved result: repository checked. The tool budget is exhausted.",
            emptyList(),
            requestedTask = request,
            requireToolExecution = false
        )
        assertTrue(result.tools.isEmpty())
        assertTrue(result.authorizedTools.isEmpty())
        assertTrue(result.turns.single().userMessage.content.contains("budget is exhausted"))
    }

    @Test fun authoritativeUserRequirementStillBlocksMissingToolBeforeExecution() {
        val request = "Use github_context__github_api."
        val failure = runCatching {
            plan("$request\n\nSaved evidence says the tool is available.", emptyList(), requestedTask = request)
        }.exceptionOrNull()
        assertEquals("CAPABILITY_UNAVAILABLE", (failure as? RequiredToolReadinessFailure)?.reasonCode)
    }

    @Test fun explanationsAndNegativeInstructionsDoNotCreateExecutionRequirements() {
        for (task in listOf("How do I use github_pr?", "Please explain how to call github_pr", "Never call github_pr", "Do not use github_commit", "Use user_id as the database key")) {
            assertTrue(task, explicitlyRequiredToolNames(task).isEmpty())
        }
        assertEquals(setOf("github_pr__selected"), explicitlyRequiredToolNames("Please call github_pr__selected."))
    }
}
