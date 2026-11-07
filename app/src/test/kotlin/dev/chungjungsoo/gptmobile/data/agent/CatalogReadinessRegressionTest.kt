package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.readiness.PreparationKind
import dev.chungjungsoo.gptmobile.data.readiness.SendPreparationPlanner
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogReadinessRegressionTest {
    private fun tool(name: String, visible: Boolean = true, authenticated: Boolean = true): ResolvedAgentTool {
        val operation = object : AgentTool {
            override val definition = AgentToolDefinition(name, "Authorized test tool", JsonObject(emptyMap()), modelVisible = visible)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Admission must not execute the operation")
        }
        return ResolvedAgentTool(operation, null, null, name, name, nativeGitHub = true, nativeGitHubAuthenticated = authenticated)
    }

    private fun directory(catalog: ProgressiveToolCatalog) = ResolvedAgentTool(catalog, null, null, catalog.definition.name, catalog.definition.name, progressiveCatalog = catalog)
    private fun failure(name: String, tools: List<ResolvedAgentTool>) = runCatching { requiredToolSchemas(setOf(name), tools) }.exceptionOrNull() as RequiredToolReadinessFailure

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun qualifiedAliasesWaitForAuthorizedCatalogInsteadOfOptionalDeadline() = runTest {
        for (name in listOf("github_context__github_api", "github_ci__github_api")) {
            assertEquals(PreparationKind.REQUIRED_WORK, SendPreparationPlanner.classify(name).kind)
            assertEquals(PreparationKind.REQUIRED_WORK, SendPreparationPlanner.classify("Call $name for this task.").kind)
            val operation = tool(name)
            val catalog = ProgressiveToolCatalog(emptyList(), name, acquireCold = { _, _ ->
                delay(650)
                listOf(operation.tool.definition)
            })
            val tools = listOf(directory(catalog))
            val loading = async { catalog.execute("load-$name", JsonObject(emptyMap())) }
            runCurrent()
            val pending = failure(name, tools)
            assertEquals("ACQUISITION_PENDING", pending.reasonCode)
            assertEquals(JsonPrimitive("tool_readiness"), pending.failureDetails["failed_stage"])
            assertEquals(JsonPrimitive(false), pending.failureDetails["dispatched"])
            assertFalse(pending.message.orEmpty().contains("Connect"))
            assertFalse(loading.await().isError)
            assertNull(catalog.acquisitionReadinessCode())
            assertEquals(listOf(operation.tool.definition), requiredToolSchemas(setOf(name), tools + operation))
        }
    }

    @Test fun timeoutCanRecoverWithoutBecomingUnavailableOrChangingAuthority() = runTest {
        val name = "github_context__github_api"
        val operation = tool(name)
        val catalog = ProgressiveToolCatalog(emptyList(), name, acquireCold = { _, _ ->
            delay(650)
            listOf(operation.tool.definition)
        })
        assertTrue(runCatching { withTimeout(250) { catalog.execute("timeout", JsonObject(emptyMap())) } }.isFailure)
        assertEquals("ACQUISITION_TIMED_OUT", failure(name, listOf(directory(catalog))).reasonCode)
        assertFalse(withTimeout(750) { catalog.execute("retry", JsonObject(emptyMap())) }.isError)
        assertNull(catalog.acquisitionReadinessCode())
    }

    @Test fun completedEmptyCatalogAndDisabledAmbiguousAndUnauthenticatedToolsAreDistinct() = runTest {
        val name = "github_ci__github_api"
        val catalog = ProgressiveToolCatalog(emptyList(), name, acquireCold = { _, _ -> emptyList() })
        catalog.execute("complete-empty", JsonObject(emptyMap()))
        assertEquals("CAPABILITY_UNAVAILABLE", failure(name, listOf(directory(catalog))).reasonCode)
        assertEquals("CAPABILITY_UNAVAILABLE", failure(name, listOf(tool(name, visible = false))).reasonCode)
        assertEquals("AMBIGUOUS_CONFIGURATION", failure("github_ci", listOf(tool("github_ci__one"), tool("github_ci__two"))).reasonCode)
        assertEquals("AUTHORIZATION_REQUIRED", failure("github_commit__one", listOf(tool("github_commit__one", authenticated = false))).reasonCode)
    }

    @Test fun referencesInExplanationsAndNegativeInstructionsDoNotRequireExecution() {
        for (task in listOf("How does github_ci__github_api work?", "Explain github_context__github_api", "Never call github_ci__github_api", "Do not use `github_context__github_api`", "Avoid github_ci__github_api")) {
            assertTrue(task, explicitlyRequiredToolNames(task).isEmpty())
        }
    }

    @Test fun guessedInitialVersionGetsOnlyOneStructuredRestartAndExactContinuationWorks() = runTest {
        val definitions = (1..20).map { tool("operation_$it").tool.definition }
        val catalog = ProgressiveToolCatalog(definitions, "list tools")
        val guessed = buildJsonObject {
            put("inventory_cursor", 0)
            put("inventory_version", "1")
        }
        val rejected = catalog.execute("first-invalid", guessed)
        assertEquals("STALE_INVENTORY", rejected.errorCode)
        assertFalse(rejected.dispatched)
        val restart = (rejected.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(false), restart["permissions_changed"])
        assertEquals(JsonPrimitive(true), restart["restart_offered"])
        val correction = restart.getValue("correction_call").jsonObject
        assertEquals(JsonPrimitive(ProgressiveToolCatalog.NAME), correction["tool"])
        assertEquals(JsonObject(emptyMap()), correction["arguments"])
        val first = (catalog.execute("corrected", correction.getValue("arguments").jsonObject).content as ToolResultContent.Json).value.jsonObject
        val continuation = first.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
        assertFalse(catalog.execute("next", continuation).isError)
        val repeated = (catalog.execute("invalid-again", guessed).content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(false), repeated["restart_offered"])
        assertFalse("correction_call" in repeated)
        assertEquals("INVALID_ARGUMENTS", catalog.execute("bad-cursor", buildJsonObject { put("inventory_cursor", "12") }).errorCode)
    }
}
