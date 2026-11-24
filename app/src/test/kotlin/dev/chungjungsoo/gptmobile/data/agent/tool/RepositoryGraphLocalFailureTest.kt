package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphIntegrityException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.PhoneTaskContextPhase
import dev.chungjungsoo.gptmobile.data.github.taskContextPhase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryGraphLocalFailureTest {
    private val args = JsonObject(mapOf("action" to JsonPrimitive("task_context_bundle"), "query" to JsonPrimitive("private task query")))

    @Test fun http200AndLocalFailureKeepSeparateEvidenceAndOfferOnlyAuthorizedIndependentReads() = runTest {
        var fallbackReads = 0
        val tool = RepositoryGraphTool("repository_graph__selected", nativeStatus = {
            fallbackReads++
            error("No automatic source fallback")
        }) {
            val request = GitHubDispatchAccounting.started(false, "/repos/owner/repo/git/trees/selected")
            GitHubDispatchAccounting.responded(false, 200, request)
            try {
                taskContextPhase(PhoneTaskContextPhase.SOURCE_GRAPH) {
                    throw IllegalArgumentException("private source contents").apply {
                        stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.data.github.GitHubCodeContext", "build", "/private/source.kt", 42))
                    }
                }
            } catch (failure: Exception) {
                throw GatewayGraphOperationException(failure, null, null, null, false, targetRevision = "a".repeat(40))
            }
        }
        val result = tool.execute("local-failure", args)
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        val receipt = requireNotNull(result.executionReceipt)
        assertTrue(result.isError)
        assertTrue(result.dispatched)
        assertEquals(JsonPrimitive(1), receipt["read_requests"])
        assertEquals(JsonPrimitive(0), receipt["read_http_failures"])
        assertEquals(JsonPrimitive(200), receipt["http_status"])
        assertEquals(JsonPrimitive("SOURCE_GRAPH"), receipt["graph_local_phase"])
        assertEquals(JsonPrimitive("LOCAL_INVARIANT_REJECTED"), receipt["graph_local_reason"])
        assertEquals(JsonPrimitive(false), body["automatic_retry"])
        assertEquals(JsonPrimitive(false), body["graph_complete"])
        assertTrue(body.containsKey("native_read_alternatives"))
        assertTrue(body["source_inspection"].toString().contains("github_search"))
        assertEquals(0, fallbackReads)
        assertFalse((body.toString() + receipt.toString()).contains("private source"))
        assertFalse((body.toString() + receipt.toString()).contains("private task"))
        assertFalse(receipt.toString().contains("/private/"))
        assertTrue((receipt.getValue("exception_application_frames") as JsonArray).any { it.toString().contains("GitHubCodeContext.build:42") })

        val event = ToolEvent("event", "run", 1, result.callId, null, null, "repository_graph", tool.definition.name, args.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val fields = ToolReportDiagnostics.fields(event, result)
        val context = IssueReportContext.fromFields("TOOL_RESULT", fields, 1L)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("SOURCE_GRAPH"), diagnostics.values["graph_local_phase"])
        assertTrue("GRAPH_LOCAL_CONTEXT_FAILURE_V1" in diagnostics.requiredServiceFeatures())
        assertTrue(diagnostics.isValid())
        val report = IssueReport("report", "debug", "1", 1, "abcdef0", "16", 36, "fixture", context = context)
            .withCapturedException(receipt)
        assertTrue(report.frames.any { it.contains("GitHubCodeContext.build:42") })
        assertFalse(report.approvedPayload().contains("private"))
        assertFalse(ToolReportDiagnostics.fields(event.copy(callId = "another-call"), result).containsKey("graph_local_phase"))
    }

    @Test fun sourceIdentityRejectionNeverOffersTheLocalAssemblyFallback() = runTest {
        val tool = RepositoryGraphTool("repository_graph") {
            throw GatewayGraphIntegrityException("GRAPH_REVISION_MISMATCH", "Selected source changed")
        }
        val result = tool.execute("changed", args)
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        assertTrue(result.isError)
        assertFalse(body.containsKey("native_read_alternatives"))
        assertFalse(requireNotNull(result.executionReceipt).containsKey("graph_local_phase"))
    }
}
