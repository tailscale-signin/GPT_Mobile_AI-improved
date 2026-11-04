package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubAcceptanceLedgerTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val tools = listOf(AgentToolDefinition("github_context", "Context", json("""{"properties":{"action":{"const":"status"}}}""")))
    private val task = "Implement the feature"
    private val sha = "a".repeat(40)

    @Test fun catalogDiscoveryAndAuthorizationNeverClaimSuccessfulExecution() {
        val row = GitHubTestChecklist.rows(emptyMap(), tools, "test all tools").single().jsonObject
        val readiness = row.getValue("readiness").jsonObject
        assertEquals(JsonPrimitive(true), readiness["discovered"])
        assertEquals(JsonPrimitive("AUTHORIZED"), readiness["authorization"])
        assertEquals(JsonPrimitive("UNKNOWN"), readiness["availability"])
        assertEquals(JsonPrimitive(false), readiness["tested"])
        assertEquals(JsonPrimitive(false), readiness["successfully_tested"])
        assertEquals(JsonPrimitive("NOT_TESTED"), row["acceptance_state"])
        assertEquals(JsonPrimitive(false), GitHubAcceptanceLedger.snapshot(capabilities = JsonArray(listOf(row)))["completion_verified"])
    }

    @Test fun historicalReadinessUsesExactCurrentDefinitionAndPermissiveSchemas() {
        val receipt = json("""{"receipt_id":"source","trusted":true,"success":true,"dispatched":true,"tool":"github_context__active","arguments":{"action":"status"},"result":{}}""")
        val definitions = listOf(AgentToolDefinition("github_context__active", "Context", json("""{"type":"object"}""")))
        val rows = GitHubTestChecklist.rows(mapOf("source" to receipt), definitions, "test all tools")
        val current = rows.map { it.jsonObject }.single { it["id"] == JsonPrimitive("github_context:status") }
        assertEquals(JsonPrimitive("PASS"), current["acceptance_state"])
        assertEquals(JsonPrimitive("AUTHORIZED"), current.getValue("readiness").jsonObject["authorization"])
        val foreign = JsonObject(receipt + ("tool" to JsonPrimitive("github_context__other")))
        val excluded = GitHubTestChecklist.rows(mapOf("foreign" to foreign), definitions, "test all tools").map { it.jsonObject }.single { it["id"] == JsonPrimitive("github_context:status") }
        assertEquals(JsonPrimitive("BLOCKED"), excluded["acceptance_state"])
        assertEquals(JsonPrimitive("NOT_AUTHORIZED"), excluded.getValue("readiness").jsonObject["authorization"])
        assertEquals(JsonPrimitive(false), excluded.getValue("readiness").jsonObject["tested"])
    }

    @Test fun excludedCriteriaRemainVisibleButCannotBlockOrEstablishCompletion() {
        val excluded = json("""{"id":"optional","requirement":"Other repository","status":"OUT_OF_SCOPE"}""")
        val passed = json("""{"id":"required","requirement":"Selected repository","status":"PASSED"}""")
        assertEquals(JsonPrimitive(false), GitHubAcceptanceLedger.snapshot(requirements = JsonArray(listOf(excluded)))["completion_verified"])
        val optionalFailure = json("""{"id":"optional-failure","requirement":"Optional check","required":false,"status":"FAILED"}""")
        val ledger = GitHubAcceptanceLedger.snapshot(requirements = JsonArray(listOf(passed, excluded, optionalFailure)))
        assertEquals(JsonPrimitive(true), ledger["completion_verified"])
        assertEquals(3, ledger.getValue("requirements").jsonArray.size)
        assertEquals(JsonPrimitive(false), GitHubAcceptanceLedger.snapshot(requirements = JsonArray(listOf(optionalFailure)))["completion_verified"])
    }

    @Test fun deniedDispatchIsBlockedAndNeverCountedAsTestExecution() {
        val receipt = json("""{"receipt_id":"denied","trusted":true,"success":false,"dispatched":false,"tool":"github_context","arguments":{"action":"status"},"error_code":"PERMISSION_DENIED","result":{}}""")
        val row = GitHubTestChecklist.rows(mapOf("denied" to receipt), tools, "test all tools").single().jsonObject
        assertEquals(JsonPrimitive("BLOCKED"), row["acceptance_state"])
        val readiness = row.getValue("readiness").jsonObject
        assertEquals(JsonPrimitive("DENIED"), readiness["authorization"])
        assertEquals(JsonPrimitive("UNAVAILABLE"), readiness["availability"])
        assertEquals(JsonPrimitive(false), readiness["tested"])
    }

    @Test fun runningAndInterruptedAcceptanceSurvivesRestorationBeforeAnyFinalAnswer() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task-binding", "owner/repo", "main", setOf("github_context"), save = { id, value -> saved[id] = value })
        book.retainProgress(task, tools)
        val progress = saved.values.last()
        assertEquals(JsonPrimitive("IN_PROGRESS"), progress["phase"])
        assertEquals(JsonPrimitive("NOT_TESTED"), progress.getValue("acceptance_ledger").jsonObject.getValue("requirements").jsonArray.single().jsonObject["state"])
        book.retainProgress(task, tools, "LOCAL_SERVICE_UNREACHABLE")
        val restored = GitHubEvidenceBook("task-binding", "owner/repo", "main", emptySet(), latestVerdict = { saved.values.last() })
        val diagnostic = requireNotNull(restored.diagnostic())
        assertEquals(JsonPrimitive("INTERRUPTED"), diagnostic["phase"])
        assertEquals(JsonPrimitive(false), diagnostic["response_delivered"])
        assertEquals(JsonPrimitive("BLOCKED"), diagnostic.getValue("acceptance_ledger").jsonObject.getValue("requirements").jsonArray.single().jsonObject["state"])
    }

    @Test fun acceptedMutationReceiptIsDurableEvenWhenReviewProjectionIsFull() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_context", "github_commit"), save = { id, value -> saved[id] = value }, retentionLimitRecords = 1)
        book.observe(ProviderEvent.ToolCall("status", "github_context", json("""{"action":"status"}""")), AgentToolResult("status", ToolResultContent.Json(json("""{"ok":true}""")), false))
        book.observe(ProviderEvent.ToolCall("commit", "github_commit", json("""{"action":"commit","branch":"main"}""")), AgentToolResult("commit", ToolResultContent.Json(json("""{"commit_sha":"$sha"}""")), false))
        assertFalse(book.complete())
        assertEquals(1, book.snapshot().first.size)
        assertEquals(2, saved.size)
        assertEquals(JsonPrimitive("commit"), saved.values.last()["call_id"])
        assertEquals(JsonPrimitive(true), saved.values.last()["success"])
    }

    @Test fun anotherSavedViewOrOlderCursorSetCannotClearAPersistenceLimitation() = runBlocking {
        val book = GitHubEvidenceBook("task", "owner/repo", "work", setOf("github_commit"))
        suspend fun observe(id: String, view: String, status: String, cursors: Int, first: Int = 1) {
            val execution = buildJsonObject {
                put("saved_view_id", view)
                put("saved_view_persistence", status)
                put("saved_view_issued_cursors", cursors)
                put("saved_view_cursor_ids", JsonArray((first until first + cursors).map { JsonPrimitive("$view:$it:1536") }))
            }
            book.observe(
                ProviderEvent.ToolCall(id, "github_commit", json("""{"view_cursor":"$view:0:1536"}""")),
                AgentToolResult(id, ToolResultContent.Json(json("""{"retained":true}""")), false, dispatched = false, sharedResult = true, executionReceipt = execution)
            )
        }
        observe("missing", "original-view", "UNAVAILABLE", 3)
        observe("other", "another-view", "RETAINED", 3)
        observe("older", "original-view", "RETAINED", 2)
        observe("same-count-different-handles", "original-view", "RETAINED", 3, first = 4)
        assertEquals(JsonPrimitive(false), book.retentionStatus()["evidence_retention_complete"])
        observe("repaired", "original-view", "RETAINED", 3)
        assertEquals(JsonPrimitive(true), book.retentionStatus()["evidence_retention_complete"])
    }

    @Test fun pendingApiSuccessDoesNotCompleteRequirementsAndFailedTestsAreExplicit() {
        val incomplete = GitHubRequestCompletion.evaluate(task, listOf(json("""{"status":"VERIFIED"}""")), JsonObject(emptyMap()), mapOf("api" to json("""{"trusted":true,"success":true}""")))
        assertEquals(JsonPrimitive(false), incomplete["completion_verified"])
        assertEquals(JsonPrimitive("NOT_TESTED"), incomplete.getValue("requirements").jsonArray.single().jsonObject["acceptance_state"])
        val failed = evaluate("failure")
        assertEquals(JsonPrimitive(false), failed["completion_verified"])
        assertEquals(JsonPrimitive("FAIL"), failed.getValue("requirements").jsonArray.single().jsonObject["acceptance_state"])
        assertEquals(JsonPrimitive(true), evaluate("success")["completion_verified"])
    }

    @Test fun passingTestSubsetCannotHideAnotherFailedJobOrLaterObservation() {
        assertEquals(JsonPrimitive(false), evaluate("success", otherConclusion = "failure")["completion_verified"])
        assertEquals(JsonPrimitive(false), evaluate("success", latestConclusion = "failure")["completion_verified"])
        assertEquals(JsonPrimitive(true), evaluate("success", otherConclusion = "skipped")["completion_verified"])
    }

    @Test fun ledgerProjectionIsRefreshedAfterRequirementCoverageChanges() {
        val successful = GitHubAcceptanceLedger.attach(evaluate("success"))
        val blocked = JsonObject(successful + ("requirements" to JsonArray(listOf(json("""{"id":"remaining","requirement":"Device testing","status":"UNTESTED"}""")))))
        val refreshed = GitHubAcceptanceLedger.attach(blocked).getValue("acceptance_ledger").jsonObject
        assertEquals(JsonPrimitive(false), refreshed["completion_verified"])
        assertEquals(JsonPrimitive("NOT_TESTED"), refreshed.getValue("requirements").jsonArray.single().jsonObject["state"])
    }

    private fun evaluate(conclusion: String, otherConclusion: String? = null, latestConclusion: String? = null): JsonObject {
        fun receipt(action: String, result: JsonObject) = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", if (action == "commit") "github_commit" else "github_ci")
            put("source_head_sha", sha)
            put("arguments", buildJsonObject { put("action", action) })
            put("result", result)
        }
        fun jobs(outcome: String) = json("""{"run_id":7,"run_attempt":1,"head_sha":"$sha","page":1,"has_more":false,"total_count":${if (otherConclusion == null) 1 else 2},"jobs":[{"id":9,"name":"Unit tests","status":"completed","conclusion":"$outcome"}${if (otherConclusion == null) "" else ",{\"id\":10,\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"$otherConclusion\"}"}]}""")
        val records = linkedMapOf("implementation" to receipt("commit", JsonObject(emptyMap())), "tests" to receipt("jobs", jobs(conclusion)))
        latestConclusion?.let { records["latest"] = receipt("jobs", jobs(it)) }
        val answer = json("""{"acceptance":{"${GitHubRequestCompletion.id(task)}":{"implementation_receipts":["implementation"],"test_receipts":["tests"]}}}""")
        return GitHubRequestCompletion.evaluate(task, listOf(json("""{"status":"VERIFIED"}""")), answer, records)
    }
}
