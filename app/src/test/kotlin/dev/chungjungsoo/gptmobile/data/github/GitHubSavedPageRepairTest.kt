package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedPageRepairTest {
    private val modelName = "github_context__selected_api"
    private val tools = listOf(AgentToolDefinition(modelName, "Selected repository reads", json("""{"properties":{"action":{"const":"branches"},"view_cursor":{"type":"string"}}}""")))
    private val task = "test all tools read only"

    @Test fun contextConflictRepairsOnlyTheIssuedLocalReadAndRestoresAfterRestart() {
        val views = GitHubResultViews(pageBytes = 2048)
        val source = views.present(
            "selected-scope",
            "github_context",
            modelName,
            AgentToolResult("source", ToolResultContent.Json(JsonPrimitive("repository evidence ".repeat(400))), false),
            json("""{"action":"branches","owner":"team","repo":"project","commit_sha":"${"a".repeat(40)}"}""")
        )
        val page = result(source)
        val args = (page.getValue("continuation_call") as JsonObject).getValue("arguments") as JsonObject
        val cursor = (args.getValue("view_cursor") as JsonPrimitive).content
        val rejectedArgs = JsonObject(args + json("""{"action":"resolve","commit_sha":"${"b".repeat(40)}"}"""))
        val rejected = views.read("selected-scope", "github_context", modelName, cursor, "rejected", JsonObject(rejectedArgs - "view_cursor"))
        assertTrue(rejected.isError)
        assertFalse(rejected.dispatched)
        assertEquals(JsonPrimitive("SAVED_VIEW_CONTEXT_MISMATCH"), result(rejected)["reason"])
        assertEquals(json("""{"fields":["action","commit_sha"]}""")["fields"], result(rejected)["conflicting_fields"])
        val records = linkedMapOf("source" to receipt(json("""{"action":"branches"}"""), source), "rejected" to receipt(rejectedArgs, rejected))
        val correction = requireNotNull(GitHubTestChecklist.nextCall(task, tools, records))
        assertEquals(modelName, correction.name)
        assertEquals(args, correction.arguments)
        val snapshot = requireNotNull(views.checkpoint("selected-scope", source)).second
        val resumed = GitHubResultViews(pageBytes = 2048)
        assertTrue(resumed.restore("selected-scope", "github_context", modelName, cursor, snapshot))
        val corrected = resumed.read("selected-scope", "github_context", modelName, cursor, "correction")
        assertFalse(corrected.isError)
        assertFalse(corrected.dispatched)
        assertTrue(corrected.sharedResult)
        assertEquals("SAVED_VIEW_UNAVAILABLE", resumed.read("other-scope", "github_context", modelName, cursor, "foreign").errorCode)
        records["corrected"] = receipt(correction.arguments, corrected)
        assertFalse(GitHubTestChecklist.pendingPages(records).any { it["arguments"] == correction.arguments })
    }

    @Test fun legacyConflictingSelectorReceiptHasOneExactCorrectionAcrossResume() {
        val records = linkedMapOf("failure" to legacyFailure())
        val next = requireNotNull(GitHubTestChecklist.nextCall(task, tools, records))
        assertEquals(json("""{"view_cursor":"saved:1:2048"}"""), next.arguments)
        records["attempt"] = json("""{"trusted":true,"success":false,"dispatched":false,"tool":"$modelName","arguments":${next.arguments},"error_code":"SAVED_VIEW_UNAVAILABLE","result":{}}""")
        assertNoRepair(GitHubTestChecklist.nextCall(task, tools, records.toMap()))
        records["later-offset"] = legacyFailure("saved:2:2048")
        assertNoRepair(GitHubTestChecklist.nextCall(task, tools, records.toMap()))
    }

    @Test fun repairedInventoryDoesNotKeepItsSelectorConflictAsAnUnresolvedFailure() {
        val page = json("""{"trusted":true,"success":true,"dispatched":true,"tool":"$modelName","arguments":{"action":"branches","page":1},"result":{"items":[{"name":"main"}],"has_more":false},"view":{"recovery_call":{"tool":"$modelName","arguments":{"view_cursor":"saved:0:2048"}}}}""")
        val failure = legacyFailure("saved:1:2048")
        val records = linkedMapOf("inventory" to page, "failure" to failure)
        assertEquals(JsonPrimitive("incomplete or pending pagination"), (GitHubTestChecklist.inventoryCoverage(records).single() as JsonObject)["coverage"])
        records["correction"] = json("""{"trusted":true,"success":true,"dispatched":false,"tool":"$modelName","arguments":{"view_cursor":"saved:1:2048"},"result":{"local_page_complete":true}}""")
        assertEquals(JsonPrimitive("retrieval complete at observation"), (GitHubTestChecklist.inventoryCoverage(records).single() as JsonObject)["coverage"])
    }

    @Test fun rejectedArgumentsCannotWidenCursorToolRepositoryOrMutationAuthority() {
        val original = legacyFailure()
        val root = original.getValue("result") as JsonObject
        val corrections = listOf(
            json("""{"tool":"$modelName","arguments":{"view_cursor":"different:1:2048"}}"""),
            json("""{"tool":"github_context__other_api","arguments":{"view_cursor":"saved:1:2048"}}"""),
            json("""{"tool":"$modelName","arguments":{"view_cursor":"saved:1:2048","owner":"other","repo":"repository"}}"""),
            json("""{"tool":"github_commit","arguments":{"action":"branch","branch":"not-authorized"}}""")
        )
        for (correction in corrections) {
            val rejected = JsonObject(original + ("result" to JsonObject(root + ("correction_call" to correction))))
            assertNoRepair(GitHubTestChecklist.nextCall(task, tools, mapOf("failure" to rejected)))
        }
        for (replacement in listOf(
            "dispatched" to JsonPrimitive(true),
            "trusted" to JsonPrimitive(false),
            "verification_eligible" to JsonPrimitive(false),
            "error_code" to JsonPrimitive("PERMISSION_REVOKED"),
            "result" to JsonObject(root - "schema")
        )) {
            assertNoRepair(GitHubTestChecklist.nextCall(task, tools, mapOf("failure" to JsonObject(original + replacement))))
        }
    }

    @Test fun missingSelectedToolOrExhaustedBudgetCannotRunTheCorrection() {
        val records = mapOf("failure" to legacyFailure())
        assertNotNull(GitHubTestChecklist.nextCall(task, tools, records))
        assertEquals(null, GitHubTestChecklist.nextCall(task, emptyList(), records))
        assertEquals(null, GitHubTestChecklist.nextCall(task, tools.map { it.copy(modelVisible = false) }, records))
        assertEquals(null, GitHubTestChecklist.nextCall(task, tools, records, remainingToolCalls = 2))
    }

    private fun assertNoRepair(call: dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.ToolCall?) {
        assertFalse(call?.arguments?.containsKey("view_cursor") == true)
        if (call != null) {
            assertEquals(modelName, call.name)
            assertEquals(JsonPrimitive("branches"), call.arguments["action"])
        }
    }

    private fun legacyFailure(cursor: String = "saved:1:2048") = json("""{"trusted":true,"success":false,"dispatched":false,"tool":"$modelName","arguments":{"view_cursor":"$cursor","action":"resolve"},"error_code":"INVALID_ARGUMENTS","result":{"schema":"github.arguments.v1","operation":"github_context","correction_call":{"tool":"$modelName","arguments":{"view_cursor":"$cursor"}}}}""")

    private fun receipt(args: JsonObject, result: AgentToolResult) = buildJsonObject {
        put("trusted", true)
        put("success", !result.isError)
        put("dispatched", result.dispatched)
        put("tool", modelName)
        put("arguments", args)
        put("result", result(result))
        result.errorCode?.let { put("error_code", it) }
    }

    private fun result(result: AgentToolResult) = (result.content as ToolResultContent.Json).value as JsonObject
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
}
