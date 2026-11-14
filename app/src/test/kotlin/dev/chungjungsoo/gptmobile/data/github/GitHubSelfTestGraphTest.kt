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
        assertEquals(json("""{"action":"status"}"""), calls(missing(), failed).single().arguments)
        assertTrue(calls(missing(), failed, missing()).isEmpty())
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

    @Test fun unconfirmedIndexReconcilesOnceAndOnlyNewObservedProgressPermitsAnotherBatch() {
        val failed = JsonObject(graph("index", """"has_more":true""") + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GRAPH_INDEX_OUTCOME_UNKNOWN")))
        val first = calls(missing(), failed).single()
        assertEquals(first, calls(missing(), failed).single())
        assertEquals(JsonPrimitive("status"), first.arguments["action"])
        val failedStatus = JsonObject(missing() + ("success" to JsonPrimitive(false)))
        assertTrue(calls(missing(), failed, failedStatus).isEmpty())
        val progress = graph("status", """"has_more":true,"index_cursor":"confirmed-next"""")
        assertEquals(json("""{"action":"index","options":{"index_cursor":"confirmed-next"}}"""), calls(missing(), failed, progress).single().arguments)
        assertEquals(GitHubCriterionBlocker.GRAPH_INDEX_UNCONFIRMED, GitHubSelfTestGraph.blocker("query", listOf(status(), missing(), failed, missing())))
        val complete = graph("status", """"has_more":false""")
        assertEquals(JsonPrimitive("architecture"), calls(missing(), failed, complete).first().arguments["action"])
    }

    @Test fun graphPreparationRequiresTaskBindingAndReportsMapPrerequisitesInsteadOfRepositoryInputs() {
        assertTrue(GitHubSelfTestGraph.available(listOf(tool), listOf(JsonObject(status() - "binding"), JsonObject(missing() - "binding"))).isEmpty())
        fun row(vararg records: JsonObject) = GitHubTestChecklist.rows((listOf(status()) + records).mapIndexed { index, receipt -> index.toString() to receipt }.toMap(), listOf(tool), "test all tools")
            .filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive("repository_graph:query") }
        assertEquals(JsonPrimitive("GRAPH_INDEX_REQUIRED"), row(missing())["blocker_code"])
        assertEquals(JsonPrimitive("GRAPH_INDEX_REQUIRED"), row(missing(), graph("query", """"status":"NOT_INDEXED""""))["blocker_code"])
        assertEquals(JsonPrimitive("GRAPH_INPUT_REQUIRED"), row(indexed())["blocker_code"])
        val incomplete = graph("index", """"has_more":true,"index_cursor":"next"""")
        assertEquals(JsonPrimitive("CONTINUATION_REQUIRED"), row(incomplete)["blocker_code"])
        assertTrue((row(indexed())["required_inputs"] as JsonArray).toString().contains("architecture"))
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

    @Test fun pagedArchitectureSuppliesQueryAndContractsWhilePathUsesActualQueryRelationships() {
        // The gateway's paged nodes inherit the graph envelope SHA and omit
        // communities, dependencies and per-node commit_sha.
        val architecture = graph(
            "architecture",
            """
            "status":"FOUND","total_files":2,"total_relationships":1,"repository_files":2,
            "nodes":[
              {"id":"file-consumer","name":"Consumer.kt","kind":"file","path":"src/Consumer.kt","line":1,"end_line":20,"blob_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","parser":"tree-sitter"},
              {"id":"file-producer","name":"Producer.kt","kind":"file","path":"src/Producer.kt","line":1,"end_line":10,"blob_sha":"cccccccccccccccccccccccccccccccccccccccc","parser":"tree-sitter"}
            ],
            "edges":[{"source":"file-consumer","target":"file-producer","relation":"depends_on","confidence":"INFERRED"}],
            "has_more":false,"next_cursor":null,"result_cursor":null,"truncated":false,"returned_nodes":2,"returned_edges":1,"page_offset":0
            """.trimIndent()
        )
        val prepared = calls(indexed(), architecture).associateBy { it.arguments["action"] }
        assertEquals(json("""{"action":"query","query":"src/Consumer.kt","options":{"limit":8,"depth":1}}"""), prepared[JsonPrimitive("query")]?.arguments)
        assertEquals(json("""{"producer":"src/Producer.kt","consumer":"src/Consumer.kt"}"""), prepared[JsonPrimitive("contracts")]?.arguments?.get("options"))
        assertNull(prepared[JsonPrimitive("path")])
        val query = graph(
            "query",
            """
            "status":"FOUND","total_matches":2,
            "nodes":[
              {"id":"consumer-call","name":"consume","kind":"function","path":"src/Consumer.kt","line":3,"end_line":8},
              {"id":"producer-call","name":"produce","kind":"function","path":"src/Producer.kt","line":2,"end_line":5}
            ],
            "edges":[{"source":"consumer-call","target":"producer-call","relation":"calls","confidence":"RESOLVED","line":4}],
            "has_more":false,"next_cursor":null,"result_cursor":null,"truncated":false,"returned_nodes":2,"returned_edges":1,"page_offset":0
            """.trimIndent()
        )
        val selected = calls(indexed(), architecture, query).associateBy { it.arguments["action"] }
        listOf("node", "callers", "callees", "impact").forEach { action ->
            assertEquals(JsonPrimitive("consumer-call"), selected[JsonPrimitive(action)]?.arguments?.get("symbol"))
        }
        assertEquals(json("""{"source":"consumer-call","target":"producer-call","depth":2}"""), selected[JsonPrimitive("path")]?.arguments?.get("options"))
    }

    @Test fun pagedSelectorsIgnoreForeignNodeRevisionsAndNonStringPaths() {
        val architecture = graph(
            "architecture",
            """
            "nodes":[
              {"id":"foreign","path":"foreign.kt","commit_sha":"${"b".repeat(40)}"},
              {"id":"invalid","path":42},
              {"id":"","path":"missing-id.kt"},
              {"id":"selected-file","kind":"file","path":"src/Selected.kt","commit_sha":"$sha"}
            ],"edges":[],"has_more":false,"returned_nodes":4,"returned_edges":0,"page_offset":0
            """.trimIndent()
        )
        val selected = calls(indexed(), architecture).associateBy { it.arguments["action"] }
        assertEquals(JsonPrimitive("src/Selected.kt"), selected[JsonPrimitive("query")]?.arguments?.get("query"))
        assertEquals(JsonPrimitive("selected-file"), selected[JsonPrimitive("node")]?.arguments?.get("symbol"))
        assertNull(selected[JsonPrimitive("path")])
        assertNull(selected[JsonPrimitive("contracts")])
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
