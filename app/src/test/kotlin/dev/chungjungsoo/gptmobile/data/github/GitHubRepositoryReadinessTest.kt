package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRepositoryReadinessTest {
    private val tool = GitHubOperationTool.definition("github_repository")
    private val tools = listOf(tool)
    private val task = "Test all authorized GitHub integration tools and verify cleanup."
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun receipt(args: String, result: String) = json("""{"trusted":true,"success":true,"dispatched":true,"tool":"github_repository","arguments":$args,"result":$result}""")

    @Test fun capabilitiesNeedsNoSourceAndStatusIsConditionalWithoutALifecycle() {
        val ready = GitHubSelfTestReads.available(tools, emptyList())
        assertEquals(listOf(json("""{"action":"capabilities"}""")), ready.map { it.arguments })
        val checkpoint = GitHubTestChecklist.checkpoint(task, emptyMap(), tools, 203)
        val rows = checkpoint.getValue("capabilities").jsonArray.map { it.jsonObject }
        val capabilities = rows.single { it["id"] == JsonPrimitive("github_repository:capabilities") }
        val status = rows.single { it["id"] == JsonPrimitive("github_repository:status") }
        assertEquals(JsonPrimitive("READY_UNTESTED"), capabilities["applicability"])
        assertEquals(JsonPrimitive("UNTESTED"), capabilities["status"])
        assertEquals(JsonPrimitive("CONDITIONAL"), status["status"])
        assertEquals(JsonPrimitive(false), status["required"])
        assertEquals(JsonPrimitive("CONDITIONAL_RESOURCE_NOT_PRESENT"), status["applicability"])
        assertEquals(JsonPrimitive(1), checkpoint.getValue("preparation").jsonObject["ready_read_count"])
        assertFalse(GitHubTestChecklist.requiresLiveTransport("github_repository", "capabilities"))
        val observed = receipt("""{"action":"capabilities"}""", """{"schema":"github.repository-lifecycle.v1","native_create":true}""")
        assertTrue(GitHubSelfTestReads.available(tools, listOf(observed)).isEmpty())
        assertEquals(JsonPrimitive("PASSED"), GitHubTestChecklist.rows(mapOf("capability" to observed), tools, task).filterIsInstance<JsonObject>().single { it["id"] == capabilities["id"] }["status"])
    }

    @Test fun nativeAndGatewayStatusUseOnlyTheirRetainedOperationInputs() {
        val native = receipt(
            """{"action":"fork","destination_owner":"fixture-owner","destination_repo":"fixture-copy","owner_type":"user","visibility":"private","source_owner":"upstream","source_repo":"sample"}""",
            """{"operation":"fork_repository","status":"PENDING","repository_id":42}"""
        )
        val gateway = receipt(
            """{"action":"import","destination_owner":"fixture-owner","destination_repo":"fixture-import","owner_type":"user","visibility":"private","source_owner":"upstream","source_repo":"sample","source_ref":"main","copy_mode":"history"}""",
            """{"operation":"import_repository","status":"RUNNING","operation_id":"repoop_${"a".repeat(32)}"}"""
        )
        for ((observed, expected) in listOf(
            native to json("""{"action":"status","operation":"fork_repository","destination_owner":"fixture-owner","destination_repo":"fixture-copy","owner_type":"user","visibility":"private","expected_repository_id":42}"""),
            gateway to json("""{"action":"status","operation":"import_repository","operation_id":"repoop_${"a".repeat(32)}"}""")
        )) {
            val call = GitHubSelfTestReads.available(tools, listOf(observed)).single { it.arguments["action"] == JsonPrimitive("status") }
            assertEquals(expected, call.arguments)
            assertTrue(ToolArgumentValidator.errors(call.arguments, tool.inputSchema).isEmpty())
            assertTrue(GitHubOperationArguments.errors("github_repository", call.arguments).isEmpty())
            val completed = receipt(call.arguments.toString(), """{"status":"VERIFIED"}""")
            assertTrue(GitHubSelfTestReads.available(tools, listOf(observed, completed)).none { it.arguments["action"] == JsonPrimitive("status") })
        }
    }

    @Test fun missingLifecycleIdAndCurrentSchemaRequirementsAreReportedExactly() {
        val incomplete = receipt(
            """{"action":"create","destination_owner":"fixture-owner","destination_repo":"fixture-copy","owner_type":"user","visibility":"private"}""",
            """{"operation":"create_repository","status":"RECONCILE_REQUIRED"}"""
        )
        val prerequisites = requireNotNull(GitHubRepositoryReadiness.read("status", tools, listOf(incomplete)))
        assertFalse(prerequisites.conditional)
        assertNull(prerequisites.arguments)
        assertTrue(prerequisites.requiredInputs.any { "expected_repository_id" in it })
        assertTrue(ToolArgumentValidator.errors(json("""{"action":"status"}"""), tool.inputSchema).isNotEmpty())
        val rows = GitHubTestChecklist.checkpoint(task, mapOf("creation" to incomplete), tools, 203)
        val blocker = rows.getValue("preparation").jsonObject.getValue("blockers").jsonArray.map { it.jsonObject }.single { it["code"] == JsonPrimitive("REPOSITORY_LIFECYCLE_REQUIRED") }
        assertTrue(blocker.getValue("required_inputs").jsonArray.any { "expected_repository_id" in it.toString() })
        assertFalse(blocker.getValue("required_inputs").toString().contains("source path"))

        val restricted = tool.copy(inputSchema = JsonObject(tool.inputSchema + ("required" to JsonArray(listOf(JsonPrimitive("gateway_scope"))))))
        assertTrue(GitHubSelfTestReads.available(listOf(restricted), emptyList()).isEmpty())
        val exact = requireNotNull(GitHubRepositoryReadiness.read("capabilities", listOf(restricted), emptyList()))
        assertTrue(exact.requiredInputs.any { "gateway_scope" in it })
        val excluded = JsonObject(incomplete + ("verification_eligible" to JsonPrimitive(false)))
        assertTrue(requireNotNull(GitHubRepositoryReadiness.read("status", tools, listOf(excluded))).conditional)
    }
}
