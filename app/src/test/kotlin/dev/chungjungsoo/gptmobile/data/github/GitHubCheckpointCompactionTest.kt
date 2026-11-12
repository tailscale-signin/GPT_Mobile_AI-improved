package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCheckpointCompactionTest {
    @Test fun ordinaryProgressRetainsReceiptsAndRequirementsWithoutUnobservedCatalogRows() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray((listOf("status") + (1..123).map { "unused_$it" }).map(::JsonPrimitive))) }) })
        }
        val tools = listOf(AgentToolDefinition("github_context", "Status and catalog fixture", schema))
        val book = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), load = { saved[it] }, save = { id, value -> saved[id] = value })
        val args = Json.parseToJsonElement("""{"action":"status"}""").jsonObject
        val data = Json.parseToJsonElement("""{"branch":"main","commit_sha":"${"a".repeat(40)}","repository":{"default_branch":"main"}}""").jsonObject
        val call = ProviderEvent.ToolCall("status", "github_context", args)
        book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(data), false))
        val receipts = book.snapshot().first
        val task = "Review the selected branch against its base."
        book.retainProgress(task, tools)
        val progress = saved.filterKeys { it.startsWith("verification_current_") }.values.single()
        val checkpoint = progress["test_checklist"] as JsonObject
        val capabilities = checkpoint["capabilities"] as JsonArray
        assertEquals(JsonPrimitive("OBSERVED_CAPABILITIES"), checkpoint["coverage_scope"])
        assertEquals(JsonPrimitive(124), checkpoint["catalog_capability_count"])
        assertEquals(JsonPrimitive(1), checkpoint["retained_capability_count"])
        assertEquals(JsonPrimitive(123), checkpoint["omitted_unobserved_capability_count"])
        assertEquals(JsonPrimitive("github_context:status"), capabilities.single().jsonObject["id"])
        assertEquals(JsonPrimitive(false), capabilities.single().jsonObject["required"])
        assertEquals(1, ((progress["acceptance_ledger"] as JsonObject)["capability_inventory"] as JsonArray).size)
        assertTrue((progress["requirements"] as JsonArray).isNotEmpty())
        assertEquals(progress["requirements"], progress["remaining_requirements"])
        assertEquals(JsonPrimitive(false), progress["completion_verified"])
        assertEquals(JsonPrimitive(false), (progress["acceptance_ledger"] as JsonObject)["completion_verified"])
        assertFalse(progress.toString().contains("unused_123"))
        assertTrue("Observed checkpoint should be smaller than the unrelated catalog rows alone", progress.toString().length < GitHubTestChecklist.rows(receipts, tools, task).toString().length)
        val restored = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), load = { saved[it] }, retainedIds = { saved.keys.filter { it.startsWith("ev_") } })
        restored.restore()
        assertEquals(receipts, restored.snapshot().first)
        assertEquals(data, restored.snapshot().first.values.single()["result"])
    }

    @Test fun identicalCurrentSnapshotsDoNotAppendJournalWrites() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        var writes = 0
        val book = GitHubEvidenceBook("binding", "owner/repo", "main", emptySet(), load = { saved[it] }, save = { id, row ->
            writes++
            saved[id] = row
        })
        val checkpoint = Json.parseToJsonElement("""{"task_outcome":"PARTIAL","observed_at_ms":1,"capabilities":[]}""").jsonObject
        book.retainChecklist(checkpoint)
        val firstWrites = writes
        repeat(5) { book.retainChecklist(JsonObject(checkpoint + ("observed_at_ms" to JsonPrimitive(it + 2)))) }
        assertEquals(firstWrites, writes)
    }

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
        assertTrue("Repeated evidence must not duplicate unchanged acceptance transitions", events.size <= 4)
        assertTrue(events.all { it["schema"] == JsonPrimitive("github.verification-event.v1") })
        assertTrue(events.all { "capabilities" !in it && "test_checklist" !in it && "result" !in it })
        assertFalse(GitHubEvidenceBook.currentSnapshot("ev_current_" + "a".repeat(24)))
        val restored = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_context"), load = { saved[it] }, retainedIds = { saved.keys.filter { it.startsWith("ev_") } })
        restored.restore()
        assertEquals(40, restored.snapshot().first.size)
        assertTrue(restored.snapshot().first.values.all { it["result"] == data })
    }
}
