package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDispatchAccountingTest {
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
