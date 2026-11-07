package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubGraphStatusFallbackTest {
    private val arguments = JsonObject(mapOf("action" to JsonPrimitive("status")))
    private val receipt = JsonObject(mapOf("read_requests" to JsonPrimitive(1)))

    @Test fun authorizedNativeStatusKeepsItsIndependentReceiptAndFinishesApproval() = runTest {
        val events = mutableListOf<String>()
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native {
                events += "read"
                AgentToolResult(it, ToolResultContent.Text("native evidence"), false, executionReceipt = receipt)
            },
            canExecute = {
                events += "access"
                true
            },
            authorize = { _, args ->
                assertEquals(arguments, args)
                events += "authorize"
                true
            },
            finished = { _, success -> events += "finish:$success" },
            validateInherited = { events += "inherited" }
        )
        val result = fallback.execute("native-status")
        assertEquals(receipt, result.executionReceipt)
        assertFalse(result.isError)
        assertEquals(listOf("inherited", "access", "authorize", "inherited", "access", "read", "inherited", "access", "finish:true"), events)
    }

    @Test fun nativeGrantRevokedDuringFailedGraphRequestPreventsNativeDispatch() = runTest {
        var allowed = true
        var reads = 0
        var approvals = 0
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native {
                reads++
                AgentToolResult(it, ToolResultContent.Text("private"), false)
            },
            canExecute = { allowed },
            authorize = { _, _ ->
                approvals++
                true
            },
            finished = { _, _ -> }
        )
        val graph = RepositoryGraphTool("repository_graph", nativeStatus = fallback::execute) {
            allowed = false
            throw IOException("gateway disconnected")
        }
        val result = graph.execute("graph", arguments)
        val recovery = ((result.content as ToolResultContent.Json).value as JsonObject)["native_fallback"] as JsonObject
        assertEquals(JsonPrimitive(false), recovery["dispatched"])
        assertEquals(JsonPrimitive("PERMISSION_DENIED"), recovery["error_code"])
        assertEquals(0, reads)
        assertEquals(0, approvals)
    }

    @Test fun inheritedAuthorityRevokedDuringFailedGraphRequestPreventsNativeDispatch() = runTest {
        var allowed = true
        var reads = 0
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native {
                reads++
                AgentToolResult(it, ToolResultContent.Text("private"), false)
            },
            canExecute = { true },
            authorize = { _, _ -> error("Revoked inherited authority must stop before native approval") },
            finished = { _, _ -> }
        )
        val graph = RepositoryGraphTool("repository_graph", nativeStatus = fallback::execute) {
            allowed = false
            throw IOException("gateway disconnected")
        }
        withContext(RequestAuthorizationGuard("profile") { check(allowed) }) {
            assertTrue(graph.execute("graph", arguments).isError)
        }
        assertEquals(0, reads)
    }

    @Test fun revocationDuringPermissionWaitPreventsNativeDispatchAndClosesApproval() = runTest {
        var allowed = true
        var finished = false
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native { error("Permission changed while approval was pending") },
            canExecute = { allowed },
            authorize = { _, _ ->
                allowed = false
                true
            },
            finished = { _, success ->
                assertFalse(success)
                finished = true
            }
        )
        val result = fallback.execute("native-status")
        assertEquals("PERMISSION_DENIED", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(finished)
    }

    @Test fun deniedNativeApprovalDoesNotInvokeRead() = runTest {
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native { error("Native operation was not authorized") },
            canExecute = { true },
            authorize = { _, _ -> false },
            finished = { _, success -> assertFalse(success) }
        )
        assertFalse(fallback.execute("native-status").dispatched)
    }

    @Test fun revokedResultIsWithheldWhileActualDispatchReceiptSurvives() = runTest {
        var allowed = true
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native {
                allowed = false
                AgentToolResult(it, ToolResultContent.Text("private native payload"), false, executionReceipt = receipt)
            },
            canExecute = { allowed },
            authorize = { _, _ -> true },
            finished = { _, success -> assertFalse(success) }
        )
        val result = fallback.execute("native-status")
        assertTrue(result.isError)
        assertTrue(result.dispatched)
        assertEquals(receipt, result.executionReceipt)
        assertFalse(result.content.toString().contains("private native payload"))
    }

    @Test fun cancellationDuringApprovalPropagatesAndClosesApproval() = runTest {
        val cancelled = CancellationException("cancel")
        var finished = false
        val fallback = GitHubGraphStatusFallback(
            nativeStatus = native { error("Canceled fallback must not dispatch") },
            canExecute = { true },
            authorize = { _, _ -> throw cancelled },
            finished = { _, success ->
                assertFalse(success)
                finished = true
            }
        )
        assertSame(cancelled, runCatching { fallback.execute("native-status") }.exceptionOrNull())
        assertTrue(finished)
    }

    private fun native(read: suspend (String) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition("github_context", "Native repository context", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = read(callId)
    }
}
