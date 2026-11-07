package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCheckpointCompactionTest {
    @Test fun immediateStagePublicationIsRecordedAsARemoteMutationAndChangesTheEvidenceEpoch() = runTest {
        val book = GitHubEvidenceBook("binding", "owner/repo", "fixture", setOf("github_stage_patch"))
        for (immediate in listOf(false, true)) {
            val arguments = Json.parseToJsonElement("""{"commit_immediately":$immediate}""").jsonObject
            val call = ProviderEvent.ToolCall("stage-$immediate", "github_stage_patch", arguments)
            book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(JsonObject(emptyMap())), false))
        }
        val (receipts, epoch) = book.snapshot()
        assertEquals(listOf(JsonPrimitive(false), JsonPrimitive(true)), receipts.values.map { it["remote_mutation"] })
        assertEquals(1L, epoch)
    }

    @Test fun currentSnapshotsReplaceOnlyProjectionsWhileReceiptsAndCompactTransitionsRemain() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        val schema = Json.parseToJsonElement("""{"properties":{"action":{"enum":["status"]}}}""").jsonObject
        val tools = listOf(AgentToolDefinition("github_context", "Status", schema))
        val book = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), load = { saved[it] }, save = { id, value ->
            if (!GitHubEvidenceBook.currentSnapshot(id) && id in saved) assertEquals("Immutable receipt changed", saved[id], value)
            saved[id] = value
        })
        val args = Json.parseToJsonElement("""{"action":"status"}""").jsonObject
        val data = Json.parseToJsonElement("""{"branch":"main","commit_sha":"${"a".repeat(40)}","repository":{"default_branch":"main"}}""").jsonObject
        repeat(40) { index ->
            val call = ProviderEvent.ToolCall("status-$index", "github_context", args)
            book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(data), false))
            book.retainProgress("Test all GitHub tools read-only", tools)
            book.retainChecklist(GitHubTestChecklist.checkpoint("Test all GitHub tools read-only", book.snapshot().first, tools))
        }
        assertEquals(40, saved.keys.count { it.startsWith("ev_") })
        assertEquals(2, saved.keys.count(GitHubEvidenceBook::currentSnapshot))
        val events = saved.filterKeys { it.startsWith("verification_event_") }.values
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it["schema"] == JsonPrimitive("github.verification-event.v1") })
        assertTrue(events.all { "capabilities" !in it && "test_checklist" !in it && "result" !in it })
        assertFalse(GitHubEvidenceBook.currentSnapshot("ev_current_" + "a".repeat(24)))
        val restored = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), load = { saved[it] }, retainedIds = { saved.keys.filter { it.startsWith("ev_") } })
        restored.restore()
        assertEquals(40, restored.snapshot().first.size)
        assertTrue(restored.snapshot().first.values.all { it["result"] == data })
    }
}
