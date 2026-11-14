package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSelfTestGraphTest {
    private val sha = "a".repeat(40)
    private val tool = AgentToolDefinition("repository_graph__selected", "Graph", json("""{"type":"object","required":["action"],"additionalProperties":false,"properties":{"action":{"enum":["status","index","architecture","query","node","callers","callees","path","impact","contracts","diff","diagnose","notes","requirements","wiki","metrics","prs","note","federated","provider"]},"query":{"type":"string"},"symbol":{"type":"string"},"options":{"type":"object"}}}"""))
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun status() = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context__selected","arguments":{"action":"status"},"result":{"branch":"main","commit_sha":"$sha"}}""")
    private fun graph(action: String, root: String, options: String = "", head: String = sha, scope: String = "owner/repo") = json(
        """{"binding":"task","repository":"$scope","trusted":true,"success":true,"tool":"${tool.name}","arguments":{"action":"$action"$options},"result":{$root,"graph":{"repository":"$scope","branch":"main","commit_sha":"$head"}}}"""
    )
    private fun missing() = graph("status", """"status":"NOT_INDEXED","next_action":"index"""")
    private fun indexed() = graph("index", """"has_more":false""")
    private fun calls(vararg receipts: JsonObject) = GitHubSelfTestGraph.available(listOf(tool), listOf(status()) + receipts)

    @Test fun missingGraphPreparesIndexAndEveryConfirmedBatchUsesItsExactCursor() {
        assertTrue(calls().isEmpty())
        assertEquals(json("""{"action":"index"}"""), calls(missing()).single().arguments)
        val first = graph("index", """"has_more":true,"index_cursor":"next-1"""")
        val next = calls(missing(), first).single()
        assertEquals(json("""{"action":"index","options":{"index_cursor":"next-1"}}"""), next.arguments)
        // Restoring the same receipts retains the same preparation identity.
        assertEquals(next, calls(missing(), first).single())
        val second = graph("index", """"has_more":true,"index_cursor":"next-2"""", """, "options":{"index_cursor":"next-1"}""")
        assertEquals(json("""{"action":"index","options":{"index_cursor":"next-2"}}"""), calls(missing(), first, second).single().arguments)
        assertEquals("architecture", (calls(missing(), first, second, indexed()).first().arguments["action"] as JsonPrimitive).content)
        assertTrue(calls(missing(), graph("index", """"has_more":true""")).isEmpty())
    }

    @Test fun failedOrUnconfirmedIndexNeverReplaysAndSelectorsNeverCrossRepositoryRevisionOrGateway() {
        val attempt = graph("index", """"has_more":true,"index_cursor":"next-1"""")
        val failed = JsonObject(attempt + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GRAPH_INDEX_OUTCOME_UNKNOWN")))
        assertTrue(calls(missing(), failed).isEmpty())
        assertTrue(calls(missing(), JsonObject(attempt + ("trusted" to JsonPrimitive(false)))).single().arguments["options"] == null)
        for (foreign in listOf(
            graph("status", """"has_more":true,"index_cursor":"foreign"""", head = "b".repeat(40)),
            graph("status", """"has_more":true,"index_cursor":"foreign"""", scope = "other/repo"),
            JsonObject(attempt + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(attempt + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(attempt + ("repository_role" to JsonPrimitive("READ_SOURCE")))
        )) {
            assertTrue(calls(foreign).isEmpty())
        }
        val other = AgentToolDefinition("repository_graph__other", tool.description, tool.inputSchema)
        assertTrue(GitHubSelfTestGraph.available(listOf(other), listOf(status(), missing())).isEmpty())
        val repeated = graph("index", """"has_more":true,"index_cursor":"same"""", """, "options":{"index_cursor":"same"}""")
        assertTrue(calls(repeated).isEmpty())
    }

    @Test fun indexedArchitectureAndQuerySupplyRealBoundedSelectors() {
        val architecture = graph("architecture", """"communities":[{"files":["src/Producer.kt","src/Consumer.kt"]}],"dependencies":{"src/Consumer.kt":["src/Producer.kt"]}""")
        val prepared = calls(indexed(), architecture).associateBy { it.arguments["action"] }
        assertEquals(json("""{"action":"query","query":"src/Producer.kt","options":{"limit":8,"depth":1}}"""), prepared[JsonPrimitive("query")]?.arguments)
        assertEquals(json("""{"action":"contracts","options":{"producer":"src/Producer.kt","consumer":"src/Consumer.kt"}}"""), prepared[JsonPrimitive("contracts")]?.arguments)
        val query = graph("query", """"nodes":[{"id":"node-1","path":"src/Producer.kt","commit_sha":"$sha"},{"id":"node-2","path":"src/Consumer.kt","commit_sha":"$sha"}],"edges":[{"source":"node-1","target":"node-2"}]}""".dropLast(1))
        val selected = calls(indexed(), architecture, query).associateBy { it.arguments["action"] }
        listOf("node", "callers", "callees", "impact").forEach { action -> assertEquals(JsonPrimitive("node-1"), selected[JsonPrimitive(action)]?.arguments?.get("symbol")) }
        assertEquals(json("""{"source":"node-1","target":"node-2","depth":2}"""), selected[JsonPrimitive("path")]?.arguments?.get("options"))
        assertNull(selected[JsonPrimitive("diff")])
        assertNull(selected[JsonPrimitive("diagnose")])
        assertTrue(selected.keys.none { it in listOf("note", "federated", "provider").map(::JsonPrimitive) })
        assertTrue(GitHubSelfTestGraph.requiredInputs("diff", listOf(status(), indexed())).single().contains("base_sha"))
    }

    @Test fun diagnoseUsesOnlyObservedLogsForTheExactRevision() {
        fun logs(head: String) = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"logs","expected_head_sha":"$head"},"result":{"excerpts":[{"line":1,"text":"error in src/Producer.kt"}]}}""")
        assertTrue(calls(indexed(), logs("b".repeat(40))).none { it.arguments["action"] == JsonPrimitive("diagnose") })
        val diagnose = calls(indexed(), logs(sha)).single { it.arguments["action"] == JsonPrimitive("diagnose") }
        assertEquals(json("""{"log":"error in src/Producer.kt"}"""), diagnose.arguments["options"])
    }

    @Test fun partialIndexStaysBlockedWithItsContinuationAndPreservesVerificationBudget() {
        val partial = graph("index", """"has_more":true,"index_cursor":"next"""")
        val receipts = linkedMapOf("selected" to status(), "graph" to missing(), "index" to partial)
        val row = GitHubTestChecklist.rows(receipts, listOf(tool), "test all tools").filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive("repository_graph:index") }
        assertEquals(JsonPrimitive("BLOCKED"), row["status"])
        assertEquals(JsonPrimitive("CONTINUATION_REQUIRED"), row["blocker_code"])
        assertEquals(JsonPrimitive("PARTIAL"), row["inventory_status"])
        assertEquals(json("""{"action":"index","options":{"index_cursor":"next"}}"""), (row["next_call"] as? JsonObject)?.get("arguments"))
        assertNull(GitHubTestChecklist.nextCall("test all tools", listOf(tool), receipts, remainingToolCalls = 2))
        assertEquals(JsonPrimitive("index"), GitHubTestChecklist.nextCall("test all tools", listOf(tool), receipts, remainingToolCalls = 3)?.arguments?.get("action"))
        assertTrue(GitHubSelfTestGraph.incomplete("query", graph("query", """"status":"NOT_INDEXED"""")))
        assertFalse(GitHubSelfTestGraph.incomplete("status", missing()))
        val before = GitHubTestChecklist.rows(mapOf("selected" to status(), "graph" to missing()), listOf(tool), "test all tools").filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive("repository_graph:query") }
        assertTrue((before["required_inputs"] as JsonArray).toString().contains("action=index"))
    }
}
