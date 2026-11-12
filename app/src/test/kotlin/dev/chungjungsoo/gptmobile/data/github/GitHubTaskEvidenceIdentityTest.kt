package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskEvidenceIdentityTest {
    private val originalBinding = "a".repeat(64)
    private val unrelatedBinding = "b".repeat(64)
    private val request = "Test all authorized GitHub tools. Do not merge. Budget: 10 minutes and 17 tool calls."
    private fun pointer(binding: String) = buildJsonObject { put("binding", binding) }
    private fun contract(binding: String, source: Int, repository: String = "owner/repo") = buildJsonObject {
        put("schema", "github.task-contract.v1")
        put("binding", binding)
        put("repository", repository)
        put("source_message_id", source)
        put("requested_actions", request)
    }

    @Test fun sourcePointerPreservesTheOriginalReceiptsAndAcceptedCeilingsAfterAnotherTask() = runBlocking {
        val records = mutableMapOf<String, JsonObject>()
        var now = 1000L
        fun book(binding: String) = GitHubEvidenceBook(
            binding, "owner/repo", "work", setOf("github_context"), now = { now },
            load = { id -> records["$binding:$id"] },
            save = { id, value -> records["$binding:$id"] = value },
            retainedIds = { records.keys.filter { it.startsWith("$binding:ev_") }.map { it.substringAfter(':') } },
            runtimeIdentity = { JsonObject(emptyMap()) }
        )
        val original = book(originalBinding)
        original.retainTaskContract(request, 11, emptyList(), GitHubExecutionLimits.toolCalls(request, 256, GitHubWorkflowPreset.selected(GitHubWorkflowPreset.DEFAULT_ID)))
        original.observe(ProviderEvent.ToolCall("saved-read", "github_context", JsonObject(emptyMap())), AgentToolResult("saved-read", ToolResultContent.Json(buildJsonObject { put("observed", true) }), false))
        val frozen = requireNotNull(records["$originalBinding:task_contract"])
        records["$unrelatedBinding:task_contract"] = contract(unrelatedBinding, 12)
        val restored = requireNotNull(
            GitHubTaskEvidenceIdentity.restore("owner/repo", 11, "c".repeat(64), pointer(originalBinding), pointer(unrelatedBinding)) { records["$it:task_contract"] }
        )
        now += 30_000
        val resumed = book(restored.binding)
        resumed.restore()
        resumed.retainTaskContract("A new request with unlimited tools", 11, emptyList(), 999)

        assertEquals(originalBinding, restored.binding)
        assertEquals(request, restored.acceptedTask)
        assertEquals(request, resumed.acceptedTaskRequest())
        assertEquals(frozen, records["$originalBinding:task_contract"])
        assertEquals(JsonPrimitive(17), frozen["tool_call_limit"])
        assertEquals(570_000L, resumed.remainingTaskMillis())
        assertTrue(resumed.snapshot().first.values.any { it["call_id"] == JsonPrimitive("saved-read") })
    }

    @Test fun legacyDerivedBindingCanRestoreOnlyItsExactSourceContract() = runBlocking {
        val records = mapOf(originalBinding to contract(originalBinding, 11), unrelatedBinding to contract(unrelatedBinding, 12))
        val restored = GitHubTaskEvidenceIdentity.restore("owner/repo", 11, originalBinding, null, pointer(unrelatedBinding)) { records[it] }
        assertEquals(originalBinding, restored?.binding)
        assertNull(GitHubTaskEvidenceIdentity.restore("owner/repo", 13, originalBinding, null, pointer(unrelatedBinding)) { records[it] })
        assertNull(GitHubTaskEvidenceIdentity.restore("owner/other", 11, originalBinding, pointer(originalBinding), null) { records[it] })
    }

    @Test fun branchTransitionPointerRetainsTheOldBindingOnlyWhenItsSourceMatches() = runBlocking {
        val restored = GitHubTaskEvidenceIdentity.restore("owner/repo", 11, "c".repeat(64), null, pointer(originalBinding)) {
            contract(it, 11)
        }
        assertEquals(originalBinding, restored?.binding)
        assertEquals("active:source:11", GitHubTaskEvidenceIdentity.sourceKey("active", 11))
    }
}
