package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the native facade and existing public result/report APIs, including on the parent source. */
class GitHubSearchValidationEvidenceTest {
    @Test fun nativeRejectionReportsOnlySuppliedClosedFieldsInOrder() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = buildJsonObject {
                put("action", "search")
                put("query", "private-query-".repeat(20))
                put("batch_size", 0)
                put("fuzzy_threshold", 0.1)
                put("max_new_blobs", 0)
                put("context_lines", 101)
                put("private.fuzzy_threshold", "private-value")
            }
            val result = fixture.tool.execute("all-fields", arguments)
            val fields = ToolReportDiagnostics.fields(searchValidationEvent(result.callId, fixture.name, arguments), result)
            assertEquals(listOf("batch_size", "fuzzy_threshold", "max_new_blobs", "context_lines"), fields["search_validation_field_names"])
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertTrue(result.isError && !result.dispatched)
            assertEquals(JsonPrimitive(5), result.executionReceipt?.get("invalid_field_count"))
            assertEquals(JsonPrimitive(1), result.executionReceipt?.get("unknown_field_count"))
            assertFalse(fields.toString().contains("private-query"))
            assertFalse(fields.toString().contains("private-value"))
            assertFalse("search_validation_field_names" in result.executionReceipt.orEmpty())
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun validValuesQueryOnlyAndUnknownFieldsDoNotCreateSearchEvidence() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val valid = buildJsonObject {
                put("action", "search")
                put("query", "bounded")
                put("fuzzy_threshold", 0.5)
                put("context_lines", 0)
                put("max_new_blobs", 1)
                put("batch_size", 1000)
            }
            assertTrue(ToolArgumentValidator.errors(valid, fixture.tool.definition.inputSchema).isEmpty())
            val cases = listOf(
                JsonObject(valid + ("query" to JsonPrimitive("q".repeat(201)))),
                JsonObject(valid + ("private.context_lines" to JsonPrimitive(101))),
                JsonObject(valid + ("private[fuzzy_threshold]" to JsonPrimitive(0.1)))
            )
            for ((index, arguments) in cases.withIndex()) {
                val result = fixture.tool.execute("unrelated-$index", arguments)
                assertEquals("INVALID_ARGUMENTS", result.errorCode)
                assertFalse(result.dispatched)
                assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(result.callId, fixture.name, arguments), result))
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun matchingForeignFacadesAndDefinitionsCannotClaimNativeValidation() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val foreign = object : AgentTool {
                override val definition = fixture.tool.definition
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Invalid arguments must not dispatch")
            }
            val tools = listOf(
                GitHubOperationTool("github_search", foreign, "foreign-search", modelName = fixture.name),
                ToolExecutionBudget(AgentRunLimits()).bind(foreign)
            )
            for ((index, tool) in tools.withIndex()) {
                val arguments = searchValidationArguments()
                val result = tool.execute("foreign-$index", arguments)
                assertEquals("INVALID_ARGUMENTS", result.errorCode)
                assertFalse(result.dispatched)
                assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(result.callId, fixture.name, arguments), result))
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun projectionRequiresExactPersistedCallConnectionAndCompleteArguments() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = searchValidationArguments()
            val result = fixture.tool.execute("bound-call", arguments)
            val event = searchValidationEvent(result.callId, fixture.name, arguments)
            assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(event, result)["search_validation_field_names"])
            val reordered = JsonObject(arguments.entries.reversed().associate { it.toPair() })
            assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(event.copy(arguments = reordered.toString()), result)["search_validation_field_names"])
            val mismatches = listOf(
                event.copy(callId = "another-call"),
                event.copy(modelToolName = "github_search__another_connection"),
                event.copy(arguments = JsonObject(arguments + ("query" to JsonPrimitive("same-shape-different-value"))).toString()),
                event.copy(arguments = JsonObject(arguments + ("max_new_blobs" to JsonPrimitive(-1))).toString()),
                event.copy(arguments = "null"),
                event.copy(arguments = "{"),
                event.copy(arguments = " ".repeat(65_537))
            )
            mismatches.forEach { assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(it, result)) }
            val copied = result.copy(callId = "another-call")
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(event.copy(callId = copied.callId), copied))
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun reconstructedAndContradictoryReceiptsCannotBorrowNativeEvidence() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = searchValidationArguments()
            val result = fixture.tool.execute("original", arguments)
            val event = searchValidationEvent(result.callId, fixture.name, arguments)
            val raw = AgentToolResult(result.callId, result.content, true, errorCode = result.errorCode, dispatched = false, executionReceipt = result.executionReceipt)
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(event, raw))
            val receipt = requireNotNull(result.executionReceipt)
            val contradictions = listOf(
                result.copy(dispatched = true),
                result.copy(isError = false),
                result.copy(errorCode = "PERMISSION_DENIED"),
                result.copy(executionReceipt = null)
            ) + listOf(
                "failed_stage" to JsonPrimitive("tool_execution"),
                "reason_code" to JsonPrimitive("TOOL_ARGUMENT_CONSTRAINT_REJECTED"),
                "tool_schema_hash" to JsonPrimitive("0".repeat(64)),
                "dispatched" to JsonPrimitive(true),
                "retryable" to JsonPrimitive(true),
                "requires_reconciliation" to JsonPrimitive(true),
                "remote_acceptance" to JsonPrimitive("UNKNOWN"),
                "call_id" to JsonPrimitive("another-call"),
                "model_tool_name" to JsonPrimitive("github_search__foreign"),
                "dependency_admission_reasons" to JsonArray(listOf(JsonPrimitive("REPEAT_LIMIT")))
            ).map { (key, value) -> result.copy(executionReceipt = JsonObject(receipt + (key to value))) }
            contradictions.forEach { assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(event, it)) }
            val spoofed = result.copy(executionReceipt = JsonObject(receipt + ("search_validation_field_names" to JsonArray(listOf(JsonPrimitive("batch_size"))))))
            assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(event, spoofed)["search_validation_field_names"])
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun freshSecondRepairRetainsItsOwnEvidenceDespiteSharedDisplayFlag() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = searchValidationArguments()
            withContext(ToolArgumentRepairScope()) {
                val first = fixture.tool.execute("repair-one", arguments)
                val second = fixture.tool.execute("repair-two", arguments)
                assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(searchValidationEvent(second.callId, fixture.name, arguments), second)["search_validation_field_names"])
                assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(searchValidationEvent(first.callId, fixture.name, arguments), first)["search_validation_field_names"])
                assertFalse(first.sharedResult)
                assertTrue(second.sharedResult)
                assertEquals(JsonPrimitive(1), first.executionReceipt?.get("argument_repair_attempt"))
                assertEquals(JsonPrimitive(2), second.executionReceipt?.get("argument_repair_attempt"))
                assertEquals(JsonPrimitive(true), second.executionReceipt?.get("argument_repair_exhausted"))
                assertEquals(first.executionReceipt?.get("argument_repair_signature_hash"), second.executionReceipt?.get("argument_repair_signature_hash"))
                val copied = first.copy(callId = second.callId, sharedResult = true)
                assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(second.callId, fixture.name, arguments), copied))
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun cachedNativeAndReplayResultsDoNotCreateFreshObservationsEvenWithReusedIds() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = searchValidationArguments()
            val first = fixture.tool.execute("reused-id", arguments)
            assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(searchValidationEvent(first.callId, fixture.name, arguments), first)["search_validation_field_names"])
            for (id in listOf("reused-id", "new-id")) {
                val reused = fixture.tool.execute(id, arguments)
                assertTrue(reused.sharedResult && !reused.dispatched)
                assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(id, fixture.name, arguments), reused))
            }
            val call = ProviderEvent.ToolCall(first.callId, fixture.name, arguments)
            val replay = requireNotNull(GitHubReplayRecovery.repeated(call, listOf(AgentToolExchange(listOf(call), listOf(first))), "Retained rejection"))
            assertTrue(replay.sharedResult && !replay.dispatched)
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(call.callId, fixture.name, arguments), replay))
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun diagnosticEvidenceLeavesLegacyValidationAndModelEnvelopeUnchanged() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val arguments = searchValidationArguments()
            val original = fixture.tool.execute("wire-equivalence", arguments)
            val legacy = ToolArgumentValidator.rejection(original.callId, fixture.name, arguments, fixture.tool.definition.inputSchema)
            assertEquals(legacy.content, original.content)
            requireNotNull(legacy.executionReceipt).forEach { (key, value) -> assertEquals(key, value, original.executionReceipt?.get(key)) }
            val unowned = AgentToolResult(original.callId, original.content, original.isError, errorCode = original.errorCode, dispatched = original.dispatched, executionReceipt = original.executionReceipt)
            assertEquals(ToolResultEnvelope.encode(fixture.name, unowned), ToolResultEnvelope.encode(fixture.name, original))
            val forged = unowned.copy(executionReceipt = JsonObject(unowned.executionReceipt.orEmpty() + ("search_validation_field_names" to JsonArray(listOf(JsonPrimitive("max_new_blobs"))))))
            assertEquals(ToolResultEnvelope.encode(fixture.name, unowned), ToolResultEnvelope.encode(fixture.name, forged))
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(searchValidationEvent(original.callId, fixture.name, arguments), forged))
            assertEquals(0, fixture.requests)
        }
    }

    @Test fun laterArgumentMutationCannotRewriteTheOriginalObservation() = runBlocking {
        NativeSearchValidationFixture().use { fixture ->
            val backing = searchValidationArguments().toMutableMap()
            val arguments = JsonObject(backing)
            val originalEvent = searchValidationEvent("immutable-binding", fixture.name, arguments)
            val result = fixture.tool.execute(originalEvent.callId, arguments)
            backing["query"] = JsonPrimitive("changed-after-validation")
            assertEquals(listOf("max_new_blobs"), ToolReportDiagnostics.fields(originalEvent, result)["search_validation_field_names"])
            assertFalse("search_validation_field_names" in ToolReportDiagnostics.fields(originalEvent.copy(arguments = arguments.toString()), result))
            assertEquals(0, fixture.requests)
        }
    }
}

internal class NativeSearchValidationFixture(val name: String = "github_search__native_fixture") : AutoCloseable {
    var requests = 0
        private set
    private val client = HttpClient(
        MockEngine {
            requests++
            error("Schema rejection must not issue an HTTP request")
        }
    )
    val backend = GitHubTool(
        apiToken = "fixture-token",
        httpClient = client,
        modelToolName = "github__native_fixture",
        repositoryContext = GitHubRepositoryContext("fixture-owner", "fixture-repo", "main"),
        persistentState = false
    )
    val tool = GitHubOperationTool("github_search", backend, "search-validation-fixture", modelName = name)
    override fun close() = client.close()
}

/** A continuation is intentionally not eligible for first-page option clamping. */
internal fun searchValidationArguments() = buildJsonObject {
    put("action", "search")
    put("query", "bounded-private-query")
    put("index_cursor", 1)
    put("max_new_blobs", 0)
}

internal fun searchValidationEvent(callId: String, modelToolName: String, arguments: JsonObject) = ToolEvent(
    eventId = "event-$callId",
    runId = "search-validation-run",
    sequence = 0,
    callId = callId,
    connectionUidSnapshot = "native-fixture",
    connectionNameSnapshot = "Native fixture",
    toolName = "github_search",
    modelToolName = modelToolName,
    arguments = arguments.toString(),
    result = null,
    resultType = null,
    status = ToolEventStatus.RUNNING
)
