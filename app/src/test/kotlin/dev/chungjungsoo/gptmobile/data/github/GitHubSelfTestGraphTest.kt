package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSelfTestGraphTest {
    private val sha = "a".repeat(40)
    private val tool = AgentToolDefinition("repository_graph__selected", "Graph", json("""{"type":"object","required":["action"],"additionalProperties":false,"properties":{"action":{"enum":["status","index","architecture","query","node","callers","callees","path","impact","contracts","diff","diagnose","notes","requirements","wiki","metrics","prs","note","federated","provider"]},"map_source":{"type":"string","enum":["phone","gateway"]},"query":{"type":"string"},"symbol":{"type":"string"},"options":{"type":"object"}}}"""))
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun status() = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context__selected","arguments":{"action":"status"},"result":{"branch":"main","commit_sha":"$sha"}}""")
    private fun graph(action: String, root: String, options: String = "", head: String = sha, scope: String = "owner/repo") = json(
        """{"binding":"task","repository":"$scope","trusted":true,"success":true,"tool":"${tool.name}","arguments":{"action":"$action"$options},"result":{$root,"graph":{"repository":"$scope","branch":"main","commit_sha":"$head"}}}"""
    )
    private fun missing() = graph("status", """"status":"NOT_INDEXED","next_action":"index"""")
    private fun indexed() = graph("index", """"has_more":false""")
    private fun calls(vararg receipts: JsonObject) = GitHubSelfTestGraph.available(listOf(tool), listOf(status()) + receipts)
    private fun JsonObject.fromSource(source: String) = JsonObject(this + ("arguments" to JsonObject(getValue("arguments").jsonObject + ("map_source" to JsonPrimitive(source)))))
    private fun pendingGateway() = json("""{"tool":"${tool.name}","binding":"task","repository":"owner/repo","branch":"main","trusted":true,"call_id":"index-call","arguments":{"action":"index"},"execution":{"schema":"gptmobile.repository-graph.v1","call_id":"index-call","action":"index","phase":"UNKNOWN_OUTCOME","index_outcome_unknown":true,"graph_index_attempt_hash":"${"a".repeat(64)}"}}""")

    @Test fun pendingIndexUsesTheOriginalConnectionAndAttemptOncePerContinue() {
        val hash = "a".repeat(64)
        val pending = pendingGateway()
        val call = requireNotNull(GitHubSelfTestGraph.statusForPendingIndex(pending, listOf(tool), emptyList(), "continue-1"))
        assertEquals(json("""{"action":"status","map_source":"gateway","options":{"expected_index_attempt_hash":"$hash"}}"""), call.arguments)
        val completed = listOf(json("""{"call_id":"${call.callId}"}"""))
        assertNull(GitHubSelfTestGraph.statusForPendingIndex(pending, listOf(tool), completed, "continue-1"))
        assertNull(GitHubSelfTestGraph.statusForPendingIndex(pending, emptyList(), completed, "continue-2"))
        assertTrue(GitHubSelfTestGraph.statusForPendingIndex(pending, listOf(tool), completed, "continue-2") != null)
    }

    @Test fun pendingRecoveryPreservesExplicitGatewayAndRequiresTypedLegacyOwnership() {
        val legacy = pendingGateway()
        val explicit = legacy.fromSource("gateway")
        for (pending in listOf(legacy, explicit)) {
            val call = requireNotNull(GitHubSelfTestGraph.statusForPendingIndex(pending, listOf(tool), emptyList(), "request"))
            assertEquals(tool.name, call.name)
            assertEquals(JsonPrimitive("gateway"), call.arguments["map_source"])
            assertEquals(JsonPrimitive("a".repeat(64)), call.arguments.getValue("options").jsonObject["expected_index_attempt_hash"])
        }
        val execution = legacy.getValue("execution").jsonObject
        for (unowned in listOf(
            legacy.fromSource("phone"),
            legacy.fromSource("unknown"),
            JsonObject(legacy - "execution" + ("result" to execution)),
            JsonObject(legacy + ("trusted" to JsonPrimitive(false))),
            JsonObject(legacy + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(legacy - "binding"),
            JsonObject(legacy - "repository"),
            JsonObject(legacy - "branch"),
            JsonObject(legacy + ("execution" to JsonObject(execution + ("schema" to JsonPrimitive("foreign.schema"))))),
            JsonObject(legacy + ("execution" to JsonObject(execution + ("call_id" to JsonPrimitive("another-call"))))),
            JsonObject(legacy + ("execution" to JsonObject(execution + ("map_source" to JsonPrimitive("phone"))))),
            JsonObject(legacy + ("execution" to JsonObject(execution + ("action" to JsonPrimitive("query"))))),
            JsonObject(legacy + ("execution" to JsonObject(execution + ("index_outcome_unknown" to JsonPrimitive(false)))))
        )) {
            assertNull(unowned.toString(), GitHubSelfTestGraph.statusForPendingIndex(unowned, listOf(tool), emptyList(), "request"))
        }
    }

    @Test fun savedJobAndCursorFollowUpsRetainTheirExplicitMapSource() {
        val jobCalls = mutableListOf<String>()
        for (source in listOf("phone", "gateway")) {
            val queued = graph("index", """"has_more":true,"index_job":{"id":"saved-job","generation":1,"state":"QUEUED"}""").fromSource(source)
            val call = requireNotNull(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), listOf(status(), queued), "request"))
            assertEquals(json("""{"action":"status","map_source":"$source"}"""), call.arguments)
            jobCalls += call.callId
            val batch = graph("index", """"has_more":true,"index_cursor":"source-owned-cursor"""").fromSource(source)
            assertEquals(json("""{"action":"index","map_source":"$source","options":{"index_cursor":"source-owned-cursor"}}"""), calls(batch).single().arguments)
            val failed = JsonObject(batch + ("success" to JsonPrimitive(false)))
            assertEquals(json("""{"action":"status","map_source":"$source"}"""), calls(failed).single().arguments)
        }
        assertFalse("Independent map sources must not share a status-request identity", jobCalls[0] == jobCalls[1])
    }

    @Test fun phoneAndGatewayObservationsCannotConsumeEachOthersCursorOrSettleTheirJobs() {
        val phone = graph("index", """"has_more":true,"index_cursor":"same-cursor"""", """, "options":{"index_cursor":"same-cursor"}""").fromSource("phone")
        val gateway = graph("index", """"has_more":true,"index_cursor":"same-cursor"""").fromSource("gateway")
        assertEquals(json("""{"action":"index","map_source":"gateway","options":{"index_cursor":"same-cursor"}}"""), calls(phone, gateway).single().arguments)

        val queued = graph("index", """"has_more":true,"index_job":{"id":"same-job","generation":1,"state":"QUEUED"}""").fromSource("gateway")
        val completed = graph("status", """"has_more":false,"index_job":{"id":"same-job","generation":1,"state":"COMPLETED"}""")
        val phoneComplete = completed.fromSource("phone")
        val gatewayComplete = completed.fromSource("gateway")
        assertTrue(GitHubSelfTestGraph.incomplete("index", queued, listOf(status(), queued, phoneComplete)))
        assertFalse(GitHubSelfTestGraph.incomplete("index", queued, listOf(status(), queued, gatewayComplete, phoneComplete)))
    }

    @Test fun savedJobsObserveOncePerRequestAndNeverLaunchCompetingCursorBatches() {
        fun saved(action: String, state: String, complete: Boolean = false) = graph(
            action,
            """"has_more":${!complete},"index_cursor":"owned-by-job","index_job":{"id":"saved-job","generation":1,"state":"$state"}"""
        )
        val queued = saved("index", "QUEUED")
        val receipts = listOf(status(), missing(), queued)
        assertTrue(GitHubSelfTestGraph.available(listOf(tool), receipts).isEmpty())
        val first = requireNotNull(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), receipts, "request-1"))
        assertEquals(json("""{"action":"status"}"""), first.arguments)
        val running = JsonObject(saved("status", "RUNNING") + ("call_id" to JsonPrimitive(first.callId)))
        val observed = receipts + running
        assertNull(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), observed, "request-1"))
        assertTrue(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), observed, "request-2") != null)
        assertNull(GitHubSelfTestGraph.statusForSavedIndex(emptyList(), observed, "request-2"))
        assertTrue(GitHubSelfTestGraph.available(listOf(tool), observed).isEmpty())
        assertEquals(GitHubCriterionBlocker.CONTINUATION_REQUIRED, GitHubSelfTestGraph.blocker("query", observed))
        assertTrue(GitHubSelfTestGraph.requiredInputs("index", observed).single().contains("saved indexing job"))
        val completed = observed + saved("status", "COMPLETED", complete = true)
        assertNull(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), completed, "request-2"))
        assertEquals(JsonPrimitive("architecture"), GitHubSelfTestGraph.available(listOf(tool), completed).first().arguments["action"])
        assertTrue(GitHubSelfTestGraph.incomplete("index", queued, observed))
        assertFalse(GitHubSelfTestGraph.incomplete("index", queued, completed))
        for (mismatch in listOf(
            graph("status", """"has_more":false,"index_job":{"id":"other-job","generation":1,"state":"COMPLETED"}"""),
            graph("status", """"has_more":false,"index_job":{"id":"saved-job","generation":2,"state":"COMPLETED"}"""),
            graph("status", """"has_more":false,"index_job":{"id":"saved-job","generation":1,"state":"RUNNING"}"""),
            graph("status", """"has_more":false,"index_job":{"id":"saved-job","generation":1,"state":"FAILED"}"""),
            graph("status", """"has_more":false,"index_job":{"id":"saved-job","generation":1,"state":"CANCELLED"}"""),
            graph("status", """"has_more":false,"index_job":{"id":"saved-job","generation":1,"state":"UNKNOWN"}"""),
            graph("status", """"has_more":false,"index_job":{"id":"saved-job","generation":1,"state":"COMPLETED"}""", head = "b".repeat(40))
        )) {
            assertTrue(GitHubSelfTestGraph.incomplete("index", queued, observed + mismatch))
        }
    }

    @Test fun completedJobAcknowledgementGetsItsSnapshotAndForeignJobStateCannotBePolled() {
        val acknowledgement = graph("index", """"index_job":{"id":"saved-job","generation":1,"state":"COMPLETED"}""")
        assertTrue(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), listOf(status(), acknowledgement), "request") != null)
        val queued = graph("index", """"index_job":{"id":"saved-job","generation":1,"state":"QUEUED"}""")
        for (foreign in listOf(
            JsonObject(queued + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(queued + ("trusted" to JsonPrimitive(false))),
            JsonObject(queued + ("verification_eligible" to JsonPrimitive(false))),
            JsonObject(queued + ("repository_role" to JsonPrimitive("READ_SOURCE"))),
            graph("index", """"index_job":{"id":"saved-job","generation":1,"state":"QUEUED"}""", head = "b".repeat(40))
        )) {
            assertNull(GitHubSelfTestGraph.statusForSavedIndex(listOf(tool), listOf(status(), foreign), "request"))
        }
    }

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

    @Test fun localGraphIndexingStaysOutsideFixtureMutationsAndCanCompleteItsCapability() {
        val indexTool = AgentToolDefinition(tool.name, tool.description, json("""{"type":"object","required":["action"],"properties":{"action":{"enum":["status","index"]},"options":{"type":"object"}}}"""))
        val contextTool = AgentToolDefinition("github_context__selected", "Selected context", json("""{"properties":{"action":{"enum":["status"]}}}"""))
        val tools = listOf(contextTool, indexTool)
        val receipts = linkedMapOf("selected" to status(), "graph" to missing())
        fun row() = GitHubTestChecklist.rows(receipts, tools, "test all tools").filterIsInstance<JsonObject>()
            .single { it["id"] == JsonPrimitive("repository_graph:index") }
        val pending = row()
        assertEquals(JsonPrimitive("NOT_APPLICABLE"), pending["fixture_state"])
        assertFalse(pending["applicability"] == JsonPrimitive("OWNED_FIXTURE_REQUIRED"))
        assertFalse(pending["blocker_code"] == JsonPrimitive("OWNED_FIXTURE_REQUIRED"))
        assertEquals(json("""{"action":"index"}"""), (pending["next_call"] as JsonObject)["arguments"])
        assertFalse(GitHubTestChecklist.capabilitiesComplete("test all tools", receipts, tools))
        receipts["index"] = indexed()
        assertEquals(JsonPrimitive("PASSED"), row()["status"])
        assertEquals(JsonPrimitive("NOT_APPLICABLE"), row()["fixture_state"])
        assertTrue(GitHubTestChecklist.capabilitiesComplete("test all tools", receipts, tools))
        assertFalse(dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(tool.name, json("""{"action":"index"}""")))
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

    @Test fun independentReadsAndSourceDependenciesPrecedeEveryIndexBatch() {
        val context = AgentToolDefinition(
            "github_context__selected",
            "Repository",
            json("""{"type":"object","properties":{"action":{"enum":["status","repository"]},"verify_live":{"type":"boolean"}},"required":["action"],"additionalProperties":false}""")
        )
        val workflow = AgentToolDefinition(
            "github_ci__selected",
            "Workflow definitions",
            json("""{"type":"object","properties":{"action":{"enum":["definitions"]},"commit_sha":{"type":"string"},"verify_live":{"type":"boolean"}},"required":["action","commit_sha"],"additionalProperties":false}""")
        )
        val tools = listOf(tool, context, workflow)
        val repository = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"${context.name}","arguments":{"action":"repository","verify_live":true},"result":{}}""")
        val definitions = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"${workflow.name}","arguments":{"action":"definitions","commit_sha":"$sha","verify_live":true},"result":{}}""")
        val preparations = listOf(missing(), graph("index", """"has_more":true,"index_cursor":"next-page""""))
        for (preparation in preparations) {
            val receipts = listOf(status(), preparation)
            assertEquals(context.name, GitHubSelfTestReads.next(tools, receipts)?.name)
            assertEquals(workflow.name, GitHubSelfTestReads.next(tools, receipts + repository)?.name)
            val index = GitHubSelfTestReads.next(tools, receipts + repository + definitions)
            assertEquals(tool.name, index?.name)
            assertEquals(JsonPrimitive("index"), index?.arguments?.get("action"))
            assertEquals(GitHubSelfTestGraph.available(tools, receipts).single(), index)
        }
        // Restoring many unfinished batches must not restore their old priority.
        val restored = listOf(status(), missing()) + (1..110).map { page ->
            graph("index", """"has_more":true,"index_cursor":"page-$page"""", """, "options":{"index_cursor":"page-${page - 1}"}""")
        }
        assertEquals(context.name, GitHubSelfTestReads.next(tools, restored)?.name)
        assertEquals(json("""{"action":"index","options":{"index_cursor":"page-110"}}"""), GitHubSelfTestReads.next(tools, restored + repository + definitions)?.arguments)
        val failed = JsonObject(graph("index", """"has_more":true""") + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("GRAPH_INDEX_OUTCOME_UNKNOWN")))
        val reconciliation = GitHubSelfTestReads.next(tools, listOf(status(), missing(), failed))
        assertEquals(tool.name, reconciliation?.name)
        assertEquals(JsonPrimitive("status"), reconciliation?.arguments?.get("action"))
        assertTrue(GitHubSelfTestReads.isDependency(requireNotNull(reconciliation)))
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
        val architecture = graph("architecture", """"communities":[{"files":["src/Producer.kt","src/Consumer.kt"]}],"dependencies":{"src/Consumer.kt":["src/Producer.kt"]}""").fromSource("gateway")
        val prepared = calls(indexed().fromSource("gateway"), architecture).associateBy { it.arguments["action"] }
        assertEquals(json("""{"action":"query","map_source":"gateway","query":"src/Producer.kt","options":{"limit":8,"depth":1}}"""), prepared[JsonPrimitive("query")]?.arguments)
        assertEquals(json("""{"action":"contracts","map_source":"gateway","options":{"producer":"src/Producer.kt","consumer":"src/Consumer.kt"}}"""), prepared[JsonPrimitive("contracts")]?.arguments)
        val query = graph("query", """"nodes":[{"id":"node-1","path":"src/Producer.kt","commit_sha":"$sha"},{"id":"node-2","path":"src/Consumer.kt","commit_sha":"$sha"}],"edges":[{"source":"node-1","target":"node-2"}]}""".dropLast(1)).fromSource("gateway")
        val selected = calls(indexed().fromSource("gateway"), architecture, query).associateBy { it.arguments["action"] }
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
        ).fromSource("gateway")
        val prepared = calls(indexed().fromSource("gateway"), architecture).associateBy { it.arguments["action"] }
        assertEquals(json("""{"action":"query","map_source":"gateway","query":"src/Consumer.kt","options":{"limit":8,"depth":1}}"""), prepared[JsonPrimitive("query")]?.arguments)
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
        ).fromSource("gateway")
        val selected = calls(indexed().fromSource("gateway"), architecture, query).associateBy { it.arguments["action"] }
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
        assertTrue(calls(indexed().fromSource("gateway"), logs("b".repeat(40))).none { it.arguments["action"] == JsonPrimitive("diagnose") })
        val diagnose = calls(indexed().fromSource("gateway"), logs(sha)).single { it.arguments["action"] == JsonPrimitive("diagnose") }
        assertEquals(json("""{"log":"error in src/Producer.kt"}"""), diagnose.arguments["options"])
    }

    @Test fun phonePreparationKeepsSupportedReadsWithoutSchedulingGatewayOnlyAnalysis() {
        val architecture = graph("architecture", """"nodes":[{"id":"consumer","path":"src/Consumer.kt"},{"id":"producer","path":"src/Producer.kt"}],"edges":[{"source":"consumer","target":"producer","relation":"depends_on"}]""")
        val query = graph("query", """"nodes":[{"id":"consumer","path":"src/Consumer.kt"},{"id":"producer","path":"src/Producer.kt"}],"edges":[{"source":"consumer","target":"producer","relation":"call_candidate"}]""")
        val logs = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"logs","expected_head_sha":"$sha"},"result":{"excerpts":[{"text":"error in src/Consumer.kt"}]}}""")
        for (source in listOf(null, "phone")) {
            fun selected(receipt: JsonObject) = if (source == null) receipt else receipt.fromSource(source)
            val initial = calls(selected(indexed()))
            assertEquals(setOf("architecture", "metrics"), initial.map { (it.arguments["action"] as JsonPrimitive).content }.toSet())
            val reads = calls(selected(indexed()), selected(architecture), selected(query), logs)
            assertEquals(setOf("metrics", "node", "callers", "callees", "impact", "path"), reads.map { (it.arguments["action"] as JsonPrimitive).content }.toSet())
            assertTrue((initial + reads).all { it.arguments["map_source"] == source?.let(::JsonPrimitive) })
        }
        val gateway = calls(indexed().fromSource("gateway"), architecture.fromSource("gateway"), query.fromSource("gateway"), logs)
        val gatewayActions = gateway.map { (it.arguments["action"] as JsonPrimitive).content }.toSet()
        assertTrue(gatewayActions.containsAll(listOf("notes", "requirements", "wiki", "prs", "contracts", "diagnose")))
        assertTrue(gateway.all { it.arguments["map_source"] == JsonPrimitive("gateway") })
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
