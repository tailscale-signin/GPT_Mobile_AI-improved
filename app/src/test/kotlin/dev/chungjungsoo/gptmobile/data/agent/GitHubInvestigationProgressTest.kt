package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubInvestigationProgressTest {
    private fun exchange(index: Int, failure: Boolean = false): AgentToolExchange {
        val call = ProviderEvent.ToolCall("call-$index", "github_read", buildJsonObject { put("path", "File$index.kt") })
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("source"), failure, errorCode = if (failure) "SAVED_VIEW_UNAVAILABLE" else null)))
    }

    @Test fun alternatingSuccessfulReadsDoNotHideRecurringPageFailures() {
        val progress = GitHubInvestigationProgress()
        repeat(6) { assertNull(progress.observe(exchange(it, it % 2 == 0))) }
        assertNotNull(progress.observe(exchange(6, true)))
    }

    @Test fun repeatedReceiptsAreNotNewEvidenceButDifferentReadsAre() {
        val progress = GitHubInvestigationProgress()
        repeat(20) { assertNull(progress.observe(exchange(it))) }
        repeat(3) { assertNull(progress.observe(exchange(19))) }
        assertNotNull(progress.observe(exchange(19)))
    }
}
