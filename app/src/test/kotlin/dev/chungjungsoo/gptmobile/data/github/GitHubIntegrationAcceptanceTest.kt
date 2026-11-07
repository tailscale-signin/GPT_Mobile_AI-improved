package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIntegrationAcceptanceTest {
    private val task = """
        Test all authorized GitHub integration tools.
        Acceptance criteria:
        - Discover the authorized tool catalog and observe representative reads before pagination
        - Exercise permitted mutations only in an owned disposable fixture and verify cleanup
        - Bind acceptance to the running app build and exact repository revision
        - Keep device, live account and mocked test outcomes separate
        Budget: at most 256 tool calls and 60 minutes. Preserve a checkpoint and identify remaining work if the budget ends.
        [github-workflow-preset:test_integration]
    """.trimIndent()
    private val sha = "a".repeat(40)
    private val runtime = json("""{"application_id":"app.test","version_name":"1.0","version_code":1,"build_type":"debug","app_source_sha":"${"b".repeat(40)}","identity_source":"RUNNING_PROCESS_BUILD_CONFIG"}""")
    private val tools = listOf(
        tool("github_context", "status"),
        tool("github_commit", "branch", "delete_branch"),
        AgentToolDefinition(GitHubAuthorizedCatalog.TOOL, "Discover authorized tools", json("""{"type":"object","properties":{"inventory_cursor":{"type":"integer"},"inventory_version":{"type":"string"},"names":{"type":"array","items":{"type":"string"}}},"additionalProperties":false}"""))
    )
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(name, name, json("""{"properties":{"action":{"enum":${JsonArray(actions.map(::JsonPrimitive))}}}}"""))
    private fun receipt(id: String, tool: String, arguments: JsonObject, result: JsonObject, origin: String = "LIVE") = buildJsonObject {
        put("receipt_id", id)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", true)
        put("dispatched", origin == "LIVE")
        put("evidence_origin", origin)
        put("tool", tool)
        put("arguments", arguments)
        put("result", result)
        put("runtime_identity", runtime)
    }
    private fun catalog(offset: Int, count: Int): JsonObject {
        val next = offset + count
        return receipt(
            "catalog-$offset",
            GitHubAuthorizedCatalog.TOOL,
            if (offset == 0) json("{}") else json("""{"inventory_cursor":$offset,"inventory_version":"version-1"}"""),
            buildJsonObject {
                put("inventory_version", "version-1")
                put("authorized_tool_count", tools.size)
                put("inventory_offset", offset)
                put("returned", count)
                put("has_more", next < tools.size)
                put(
                    "authorized_inventory",
                    JsonArray(
                        tools.drop(offset).take(count).map { tool ->
                            buildJsonObject {
                                put("name", tool.name)
                                put("schema_fingerprint", GitHubEvidenceBook.digest(tool.inputSchema.toString()))
                            }
                        }
                    )
                )
                if (next < tools.size) put("continuation_call", json("""{"tool":"discover_available_tools","arguments":{"inventory_cursor":$next,"inventory_version":"version-1"}}"""))
            },
            "LOCAL"
        )
    }
    private fun receipts(): LinkedHashMap<String, JsonObject> {
        val branch = "codex-self-test/" + GitHubEvidenceBook.digest("tasksource").take(20)
        return linkedMapOf(
            "catalog-0" to catalog(0, 2),
            "catalog-2" to catalog(2, 1),
            "source" to receipt("source", "github_context", json("""{"action":"status"}"""), json("""{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}""")),
            "creation" to receipt("creation", "github_commit", json("""{"action":"branch","branch":"$branch","from_branch":"main","expected_head_sha":"$sha"}"""), json("""{"branch":"$branch","created":true,"receipt_verified":true,"head_sha":"$sha"}""")),
            "cleanup" to receipt("cleanup", "github_commit", json("""{"action":"delete_branch","branch":"$branch","expected_head_sha":"$sha"}"""), json("""{"deleted":true,"absence_verified":true,"branch":"$branch","observed_head_sha":"$sha"}"""))
        )
    }
    private fun acceptance(records: Map<String, JsonObject>, request: String = task) = GitHubIntegrationAcceptance.requirements(request, records, tools, runtime).filterIsInstance<JsonObject>()

    @Test fun exactFiveRequirementPromptEarnsIndependentEvidenceCredits() {
        val rows = acceptance(receipts())
        assertEquals(5, rows.size)
        assertTrue(rows.toString(), rows.all { it["acceptance_state"] == JsonPrimitive("PASS") })
        assertTrue(rows.all { (it["evidence_receipt_ids"] as JsonArray).isNotEmpty() })
        assertEquals(JsonPrimitive("NOT_TESTED"), GitHubIntegrationAcceptance.binding(receipts().values, runtime)["device_execution"])
    }

    @Test fun absentBuildBindingDoesNotEraseCatalogFixtureAndSeparationCredits() {
        val records = receipts().mapValues { (_, value) -> JsonObject(value - "runtime_identity") }
        val rows = acceptance(records)
        assertEquals(4, rows.count { it["acceptance_state"] == JsonPrimitive("PASS") })
        assertEquals(JsonPrimitive("NOT_TESTED"), rows[3]["acceptance_state"])
    }

    @Test fun anUnsupportedDeviceRequirementDoesNotPoisonOtherCriteria() {
        val rows = acceptance(receipts(), task + "\n- Run Android device smoke tests")
        assertEquals(5, rows.count { it["acceptance_state"] == JsonPrimitive("PASS") })
        assertEquals(JsonPrimitive("NOT_TESTED"), rows.last()["acceptance_state"])
        assertFalse(GitHubTestChecklist.apiComplete(task + "\n- Run Android device smoke tests", receipts(), tools))
    }

    @Test fun catalogContinuationIsExactAndTheDenominatorRemainsProvisional() {
        val records = linkedMapOf("catalog-0" to catalog(0, 2))
        val state = GitHubAuthorizedCatalog.snapshot(records, tools)
        assertEquals(JsonPrimitive(false), state["denominator_final"])
        val next = requireNotNull(GitHubAuthorizedCatalog.next(tools, records))
        assertEquals(json("""{"inventory_cursor":2,"inventory_version":"version-1"}"""), next.arguments)
        records["catalog-2"] = catalog(2, 1)
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(records, tools)["denominator_final"])
        assertNull(GitHubAuthorizedCatalog.next(tools, records))
    }

    @Test fun changedSchemaAndDuplicateCatalogPagesCannotEstablishAllToolsCoverage() {
        val records = receipts()
        val changed = tools.map { if (it.name == "github_context") tool(it.name, "status", "branches") else it }
        assertEquals(JsonPrimitive("CATALOG_CONTRACT_CHANGED"), GitHubAuthorizedCatalog.snapshot(records, changed)["blocker"])
        val terminal = records.getValue("catalog-2")
        val data = terminal["result"] as JsonObject
        val duplicate = json("""{"name":"github_context","schema_fingerprint":"${GitHubEvidenceBook.digest(tools.first().inputSchema.toString())}"}""")
        records["catalog-2"] = JsonObject(terminal + ("result" to JsonObject(data + ("authorized_inventory" to JsonArray(listOf(duplicate))))))
        assertEquals(JsonPrimitive(false), GitHubAuthorizedCatalog.snapshot(records, tools)["complete"])
        assertNull(GitHubAuthorizedCatalog.next(tools, records))
    }

    @Test fun mockedReadCannotSatisfyRepresentativeLiveReads() {
        val records = receipts()
        records["source"] = JsonObject(records.getValue("source") + ("evidence_origin" to JsonPrimitive("MOCKED")))
        val rows = acceptance(records)
        assertEquals(JsonPrimitive("NOT_TESTED"), rows[1]["acceptance_state"])
        assertEquals(JsonPrimitive("NOT_TESTED"), rows[3]["acceptance_state"])
        assertEquals(JsonPrimitive("PASS"), rows[4]["acceptance_state"])
        val classes = GitHubIntegrationAcceptance.evidenceClasses(records)
        assertEquals(JsonPrimitive(1), (classes["MOCKED"] as JsonObject)["count"])
        assertEquals(JsonPrimitive(0), (classes["CURRENT_DEVICE_EXECUTION"] as JsonObject)["count"])
    }

    @Test fun failedLiveReadbackAndLateRepresentativeReadsDoNotAcquireCredit() {
        val records = receipts()
        records["source"] = JsonObject(records.getValue("source") + ("execution" to json("""{"live_read_status":"RESPONSE_NOT_FULLY_OBSERVED"}""")))
        assertEquals(JsonPrimitive("NOT_TESTED"), acceptance(records)[1]["acceptance_state"])
        val priorPage = receipt("page", "github_context", json("""{"action":"branches","page":2}"""), json("""{"items":[],"has_more":false}"""))
        val late = linkedMapOf("page" to priorPage).apply { putAll(receipts()) }
        assertEquals(JsonPrimitive("NOT_TESTED"), acceptance(late)[1]["acceptance_state"])
    }

    @Test fun stopReasonDistinguishesReservedCallsFromExhaustionAndMissingEvidence() {
        val records = receipts()
        assertEquals(JsonPrimitive("VERIFICATION_RESERVE_REACHED"), GitHubIntegrationReport.stopReason(task, records, tools, 2)["code"])
        assertEquals(JsonPrimitive("TOOL_BUDGET_EXHAUSTED"), GitHubIntegrationReport.stopReason(task, records, tools, 0)["code"])
        val missing = GitHubIntegrationReport.stopReason(task, records, tools, 200)
        assertEquals(JsonPrimitive("ACCEPTANCE_EVIDENCE_MISSING"), missing["code"])
        assertEquals(JsonPrimitive(false), missing["budget_exhausted"])
    }

    @Test fun jobAndRunIdentifiersKeepObservationGroupsDistinct() {
        val records = (1..2).associate { id -> "job-$id" to receipt("job-$id", "github_ci", json("""{"action":"logs","job_id":$id,"run_id":10}"""), json("{}")) }
        val rows = GitHubTaskCoverage.rows(records)
        assertEquals(2, rows.size)
        assertEquals(setOf(JsonPrimitive("1"), JsonPrimitive("2")), rows.map { it.getValue("job_id") }.toSet())
        assertTrue(rows.all { it["run_id"] == JsonPrimitive("10") })
    }

    @Test fun reportUsesObservedCountsAndKeepsUnknownTransportCountersExplicit() {
        val rendered = GitHubIntegrationReport.render(task, receipts(), tools)
        assertTrue(rendered.contains("2/2 known authorized GitHub tools"))
        assertTrue(rendered.contains("recorded HTTP reads: unknown"))
        assertTrue(rendered.contains("CURRENT_DEVICE_EXECUTION"))
        assertTrue(rendered.contains("MOCKED"))
        assertTrue(rendered.contains("cleanup"))
        assertFalse(rendered.contains("89"))
        val bounded = GitHubIntegrationReport.render(task, receipts(), tools, 2)
        assertTrue(bounded.contains("Budget remaining: 2"))
        assertTrue(bounded.contains("VERIFICATION_RESERVE_REACHED"))
    }

    @Test fun discoveryCountsCannotBeSuppliedByPayloadAndIncludeOnlyRetainedReceipts() {
        val observed = catalog(0, 2)
        val payload = observed.getValue("result").jsonObject
        val poisonedCounts = JsonObject(observed + ("result" to JsonObject(payload + ("coverage" to json("""{"successful_calls":87,"dispatched_calls":87,"pages":9}""")))))
        val records = mapOf("only-receipt" to poisonedCounts)
        val state = GitHubAuthorizedCatalog.snapshot(records, tools)
        assertEquals(JsonPrimitive(1), state["discovery_calls_observed"])
        assertEquals(JsonPrimitive(1), state["discovery_calls_succeeded"])
        assertEquals(JsonPrimitive(1), state["inventory_pages_verified"])
        assertEquals(JsonPrimitive(false), state["complete"])
        val report = GitHubIntegrationReport.render(task, records, tools)
        assertTrue(report.contains("Discovery: 1/1 retained calls succeeded; 1 version-bound pages verified"))
        assertFalse(report.contains("87"))
        assertFalse(report.contains("9 pages"))
    }

    @Test fun staleCatalogOffersOneExactReadRestartWithoutAuthorityChanges() {
        fun stale(id: String) = JsonObject(receipt(id, GitHubAuthorizedCatalog.TOOL, json("""{"inventory_cursor":0,"inventory_version":"1"}"""), json("""{"schema":"tools.inventory-restart.v1","error_code":"STALE_INVENTORY","permissions_changed":false,"restart_offered":true,"correction_call":{"tool":"discover_available_tools","arguments":{}}}"""), "LOCAL") + mapOf("success" to JsonPrimitive(false), "error_code" to JsonPrimitive("STALE_INVENTORY")))
        val records = linkedMapOf("bad-first" to stale("bad-first"))
        val next = requireNotNull(GitHubAuthorizedCatalog.next(tools, records))
        assertEquals(JsonObject(emptyMap()), next.arguments)
        assertTrue(next.callId.startsWith("catalog-restart-"))
        records["bad-second"] = stale("bad-second")
        assertNull(GitHubAuthorizedCatalog.next(tools, records))
    }
}
