package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDispatchAccountingTest {
    @Test fun transportDiagnosticsSurviveAccountingWithoutChangingDispatchTruth() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        withContext(accounting) { GitHubDispatchAccounting.started(false) }
        val result = accounting.finish(
            AgentToolResult(
                "read",
                ToolResultContent.Text("Connection failed"),
                true,
                errorCode = "GITHUB_TRANSIENT_NETWORK",
                dispatched = false,
                executionReceipt = kotlinx.serialization.json.buildJsonObject {
                    put("github_transport_kind", JsonPrimitive("DNS"))
                    put("github_transport_wait_ms", JsonPrimitive(30000))
                }
            ),
            false
        )
        assertEquals(true, result.dispatched)
        assertEquals(JsonPrimitive("READ_FAILED"), result.executionReceipt?.get("phase"))
        assertEquals(JsonPrimitive("DNS"), result.executionReceipt?.get("github_transport_kind"))
        assertEquals(JsonPrimitive(30000), result.executionReceipt?.get("github_transport_wait_ms"))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
    }

    @Test fun optionalFailedReadKeepsItsEndpointAndStatusWhenParentSucceeds() = runTest {
        val accounting = GitHubDispatchAccounting(record = {})
        withContext(accounting) {
            val id = GitHubDispatchAccounting.started(false, "/repos/private/secret/branches/main/protection?token=private")
            GitHubDispatchAccounting.responded(false, 403, id)
        }
        val receipt = accounting.finish(AgentToolResult("read", ToolResultContent.Text("Other reads succeeded"), false), false).executionReceipt!!
        val failure = (receipt["failed_requests"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("BRANCH_PROTECTION"), failure["endpoint_class"])
        assertEquals(JsonPrimitive(403), failure["http_status"])
        assertEquals(JsonPrimitive(true), receipt["parent_result_successful"])
        org.junit.Assert.assertFalse(receipt.toString().contains("secret"))
        org.junit.Assert.assertFalse(receipt.toString().contains("token"))
    }

    @Test fun timeoutDoesNotProveAWriteWasRejected() = runTest {
        for (status in listOf(408, 422)) {
            val accounting = GitHubDispatchAccounting()
            withContext(accounting) {
                GitHubDispatchAccounting.started(true)
                GitHubDispatchAccounting.responded(true, status)
            }
            val result = accounting.finish(AgentToolResult("write", ToolResultContent.Text("Response $status"), true, errorCode = "GITHUB_HTTP_ERROR"), true)
            assertEquals(JsonPrimitive(if (status == 408) "OUTCOME_UNKNOWN" else "REJECTED"), result.executionReceipt?.get("phase"))
            assertEquals(JsonPrimitive(if (status == 408) 1 else 0), result.executionReceipt?.get("unknown_mutations"))
            assertEquals(JsonPrimitive(status == 408), result.executionReceipt?.get("requires_reconciliation"))
            assertEquals(if (status == 408) "RECONCILIATION_REQUIRED" else "GITHUB_HTTP_ERROR", result.errorCode)
        }
    }
}
