package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMeaningfulProgressTest {
    private fun args(path: String) = Json.parseToJsonElement("""{"path":"$path","commit_sha":"${"a".repeat(40)}"}""") as JsonObject
    private fun event(id: String?, kind: String, path: String = "src/Reader.kt", status: String? = null, sequence: Int? = null) = ApiState.GatewayProgressChanged(
        GatewayProgress(jobId = "owned-job", event = kind, toolName = "github_read", toolCallId = id, toolArgs = args(path), status = status, sequence = sequence)
    )
    private fun summaries(progress: GitHubChatProgress) = progress.tick().filterIsInstance<ApiState.ActivitySummary>()

    @Test fun newBlockerIsImmediateOnceAndReplayTextOrCountersCannotCreateAnotherFinding() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        summaries(progress)
        clock = 1000
        val failed = event("read-1", "tool_failed", sequence = 10)
        progress.observe(failed)
        assertTrue(summaries(progress).single().text.contains("failed"))
        progress.observe(failed.copy(progress = failed.progress.copy(sequence = 11, message = "The display changed")))
        progress.observe(event("retry-1", "tool_failed", sequence = 12))
        clock = 2000
        assertTrue(summaries(progress).isEmpty())
        progress.observe(ApiState.ProgressCheckpoint("Inspected 12 of 40 files"))
        clock = 3000
        assertTrue(summaries(progress).isEmpty())
    }

    @Test fun aTypedApprovalDecisionHasItsOwnImmediateActionableFinding() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        summaries(progress)
        clock = 1000
        progress.observe(event("failed", "tool_failed"))
        summaries(progress)
        progress.observe(event("approval", "tool_finished", status = "waiting_for_approval"))
        assertTrue(summaries(progress).single().text.contains("needs approval before continuing"))
        progress.observe(event("approval", "tool_finished", status = "waiting_for_approval", sequence = 23))
        assertTrue(summaries(progress).isEmpty())
    }

    @Test fun freshRecoverySupersedesAnUndeliveredBlockerAndNeverClaimsTaskCompletion() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        summaries(progress)
        clock = 1000
        progress.observe(event("failed", "tool_failed"))
        progress.observe(event("fresh", "tool_completed"))
        val text = summaries(progress).single().text
        assertTrue(text.contains("available again"))
        assertTrue(text.contains("still needs verification"))
        assertFalse(text.contains("failed"))
        assertTrue(summaries(progress).isEmpty())
    }

    @Test fun savedOrDifferentResourceSuccessCannotAnnounceRecovery() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        summaries(progress)
        clock = 1000
        progress.observe(event("failed", "tool_failed"))
        summaries(progress)
        val saved = ProviderEvent.ToolCall("saved", "github_read", args("src/Reader.kt"))
        progress.observeResult(saved, AgentToolResult(saved.callId, ToolResultContent.Text("Retained result"), false, sharedResult = true))
        progress.observe(event("saved", "tool_completed"))
        progress.observe(event("other", "tool_completed", "other/Reader.kt"))
        assertTrue(summaries(progress).isEmpty())
        progress.observe(event("fresh", "tool_completed"))
        assertTrue(summaries(progress).single().text.contains("available again"))
    }

    @Test fun immediateTransitionsDoNotMoveTheFirstOrFollowupRoutineDeadline() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        summaries(progress)
        clock = 1000
        progress.observe(event("routine-1", "tool_completed", "src/One.kt"))
        assertTrue(summaries(progress).isEmpty())
        clock = 5000
        progress.observe(event("blocked", "tool_failed", "src/Blocked.kt"))
        assertEquals(1, summaries(progress).size)
        clock = 44_999
        assertTrue(summaries(progress).isEmpty())
        clock = 45_000
        assertEquals(1, summaries(progress).size)
        progress.observe(event("routine-2", "tool_completed", "src/Two.kt"))
        clock = 179_999
        assertTrue(summaries(progress).isEmpty())
        clock = 180_000
        assertEquals(1, summaries(progress).size)
    }

    @Test fun missingDurableCallIdentityUsesNormalCadenceAndDeliveredOutputStopsPendingProgress() {
        var clock = 0L
        val progress = GitHubChatProgress { clock }
        summaries(progress)
        clock = 1000
        progress.observe(event(null, "tool_failed"))
        assertTrue(summaries(progress).isEmpty())
        progress.observe(event("blocked", "tool_blocked"))
        val answer = ApiState.Success("Saved findings are available; work remains incomplete.")
        assertEquals(answer, progress.forward(answer))
        clock = 45_000
        assertTrue(summaries(progress).isEmpty())
    }
}
