package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ConfiguredPluginTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.MeasuredAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.SharedToolCallBroker
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import dev.chungjungsoo.gptmobile.data.github.GitHubBoundRepositoryTool
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ObservedTool
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity
import dev.chungjungsoo.gptmobile.data.telemetry.tool.forStatisticsProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchValidationPipelineTest {
    @Test fun runnerCapturesItsEarlyNativeRejectionsWithoutChangingTheRepairLimit() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = searchValidationArguments()
            var rounds = 0
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    if (tools.isEmpty()) {
                        emit(ProviderEvent.TextDelta("The search remains blocked by the declared constraint."))
                    } else {
                        assertTrue("The existing correction limit must remain effective", rounds <= 2)
                        emit(ProviderEvent.ToolCall("runner-$rounds", fixture.name, arguments))
                    }
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner(AgentRunLimits(githubTask = true, maxRounds = 12)).run(provider, listOf(fixture.tool)).toList()
            val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
            assertEquals(2, results.size)
            results.forEach {
                assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(searchValidationEvent(it.callId, fixture.name, arguments), it)["search_validation_field_names"])
                assertTrue(it.isError && !it.dispatched)
                assertEquals("INVALID_ARGUMENTS", it.errorCode)
            }
            assertEquals(JsonPrimitive(1), results.first().executionReceipt?.get("argument_repair_attempt"))
            assertEquals(JsonPrimitive(2), results.last().executionReceipt?.get("argument_repair_attempt"))
            assertEquals(JsonPrimitive(true), results.last().executionReceipt?.get("argument_repair_exhausted"))
            assertEquals(3, rounds)
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun budgetForwardsNativeOwnershipThroughTheProductionWrapperChain() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val repository = GitHubRepositoryContext("fixture-owner", "fixture-repo", "main")
            val recovery = ResponseRecoveryContext(emptyList(), emptyList(), emptyList(), rootWorkId = "recovery-root")
                .reuseCompletedTool(fixture.tool, "github_search", "native-fixture", readOnly = true)
            val shared = SharedToolCallBroker().wrap("shared-turn", "native-fixture", true, recovery)
            val configured = ConfiguredPluginTool(MeasuredAgentTool(shared), PluginExecutionSettings())
            val observed = ObservedTool(configured, ToolIdentity("github", "native-fixture", fixture.name))
            val wrapped = GitHubBoundRepositoryTool(observed.withRunContext("wrapper-run").forStatisticsProfile("profile"), repository)
            val budget = ToolExecutionBudget(AgentRunLimits())
            val tool = budget.bind(wrapped)
            val arguments = searchValidationArguments()
            val before = budget.snapshot()
            val results = listOf(tool.execute("budget-one", arguments), tool.execute("budget-two", arguments))
            results.forEach {
                assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(searchValidationEvent(it.callId, fixture.name, arguments), it)["search_validation_field_names"])
                assertTrue(it.isError && !it.dispatched)
            }
            assertFalse(results.first().sharedResult)
            assertTrue(results.last().sharedResult)
            assertEquals(JsonPrimitive(true), results.last().executionReceipt?.get("argument_repair_exhausted"))
            assertEquals(before, budget.snapshot())
            assertEquals(fixture.tool.definition, tool.definition)
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun sharedFollowersCannotBorrowFreshEvidenceEvenWhenCallIdsAreReused() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executions = 0
            val delayed = object : AgentTool by fixture.tool {
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executions++
                    started.complete(Unit)
                    release.await()
                    return fixture.tool.execute(callId, arguments)
                }
            }
            val wrapped = SharedToolCallBroker().wrap("shared-turn", "native-fixture", true, delayed)
            val arguments = JsonObject(searchValidationArguments() + ("commit_sha" to JsonPrimitive("a".repeat(40))))
            val first = async(start = CoroutineStart.UNDISPATCHED) { wrapped.execute("same-call", arguments) }
            started.await()
            val follower = async(start = CoroutineStart.UNDISPATCHED) { wrapped.execute("same-call", arguments) }
            release.complete(Unit)
            val original = first.await()
            val shared = follower.await()
            val event = searchValidationEvent("same-call", fixture.name, arguments)
            assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(event, original)["search_validation_field_names"])
            assertFalse(original.sharedResult)
            assertTrue(shared.sharedResult)
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(event, shared))
            assertEquals(original.content, shared.content)
            assertEquals(original.executionReceipt, shared.executionReceipt)
            assertEquals(1, executions)
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun repositoryNormalizationKeepsEvidenceBoundToTheSubmittedArguments() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val tool = GitHubBoundRepositoryTool(fixture.tool, GitHubRepositoryContext("fixture-owner", "fixture-repo", "main"))
            for (prepared in listOf(false, true)) {
                val arguments = JsonObject(searchValidationArguments() + ("query" to JsonPrimitive("bound-$prepared")))
                val result = if (prepared) tool.prepareExecution("repository-$prepared", arguments).invoke() else tool.execute("repository-$prepared", arguments)
                val event = searchValidationEvent(result.callId, fixture.name, arguments)
                assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(event, result)["search_validation_field_names"])
                val corrected = result.executionReceipt?.get("corrected_arguments") as? JsonObject
                assertTrue(corrected != null && corrected != arguments)
                assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(event.copy(arguments = requireNotNull(corrected).toString()), result))
                val forged = result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("corrected_arguments" to JsonObject(arguments + ("max_new_blobs" to JsonPrimitive(1))))))
                assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(event, forged)["search_validation_field_names"])
                assertFalse(result.dispatched)
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun siblingOperationAndPresentedForeignConstraintCannotMintSearchEvidence() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val sibling = GitHubOperationTool("github_ci", fixture.backend, "ci-fixture", modelName = "github_ci__native_fixture")
            val ciArguments = buildJsonObject {
                put("action", "runs")
                put("fuzzy_threshold", 0.1)
            }
            val siblingResult = sibling.execute("sibling", ciArguments)
            assertEquals("INVALID_ARGUMENTS", siblingResult.errorCode)
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(siblingResult.callId, sibling.definition.name, ciArguments), siblingResult))
            val schema = fixture.tool.definition.inputSchema
            val properties = requireNotNull(schema["properties"] as? JsonObject)
            val changed = JsonObject(
                schema + (
                    "properties" to JsonObject(
                        properties + (
                            "max_new_blobs" to buildJsonObject {
                                put("type", "integer")
                                put("minimum", 10)
                            }
                            )
                    )
                    )
            )
            val presented = ToolExecutionBudget(AgentRunLimits()).bindPresented(fixture.tool, fixture.tool.definition.copy(inputSchema = changed))
            val arguments = JsonObject(searchValidationArguments() + ("max_new_blobs" to JsonPrimitive(1)))
            val result = presented.execute("foreign-constraint", arguments)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(result.callId, fixture.name, arguments), result))
            assertEquals(0, fixture.requests)
        }
    }
}
