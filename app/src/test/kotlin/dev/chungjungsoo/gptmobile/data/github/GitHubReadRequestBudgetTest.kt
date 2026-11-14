package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadRequestBudgetTest {
    @Test fun exactlyThreeReadsAreAdmittedAndTheFourthCannotReachTransport() = runBlocking {
        val accounting = GitHubDispatchAccounting(forceLiveReads = true, record = {}, maxReadRequests = 3)
        var calls = 0
        withContext(accounting) {
            repeat(3) {
                val id = GitHubDispatchAccounting.started(false)
                calls++
                GitHubDispatchAccounting.responded(false, 200, id)
            }
            val failure = runCatching {
                GitHubDispatchAccounting.started(false)
                calls++
            }.exceptionOrNull()
            assertTrue(failure is GitHubReadRequestBudgetExceeded)
        }
        assertEquals(3, calls)
        val receipt = accounting.finish(AgentToolResult("fixture", ToolResultContent.Text("fixture"), false), false).executionReceipt!!
        assertEquals(JsonPrimitive(3), receipt["read_requests"])
        assertEquals(JsonPrimitive(0), receipt["unobserved_read_requests"])
    }

    @Test fun callersWithoutTheOptionalCapRetainTheirOriginalAccounting() = runBlocking {
        val accounting = GitHubDispatchAccounting(record = {})
        withContext(accounting) {
            repeat(4) {
                val id = GitHubDispatchAccounting.started(false)
                GitHubDispatchAccounting.responded(false, 200, id)
            }
        }
        val receipt = accounting.finish(AgentToolResult("fixture", ToolResultContent.Text("fixture"), false), false).executionReceipt!!
        assertEquals(JsonPrimitive(4), receipt["read_requests"])
    }
}
