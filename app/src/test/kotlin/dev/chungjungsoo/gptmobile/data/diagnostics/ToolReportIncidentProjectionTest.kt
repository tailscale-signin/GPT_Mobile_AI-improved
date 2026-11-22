package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphHttpFailure
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphPrerequisite
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphUnavailableException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReportIncidentProjectionTest {
    @Test fun issuePaginationRejectionPreservesClosedCauseAndSchemaNamesWithoutCursorValues() {
        val call = event("""{"action":"list","page":2,"after":"private cursor value"}""")
        val receipt = buildJsonObject {
            put("issue_continuation_rejected", true)
            put("issue_continuation_state", "FILTER_MISMATCH")
            put("validation_field_names", JsonArray(listOf(JsonPrimitive("after"), JsonPrimitive("state"), JsonPrimitive("private field"))))
        }
        val result = AgentToolResult(call.callId, ToolResultContent.Text("private error detail"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false, executionReceipt = receipt)
        val fields = ToolReportDiagnostics.fields(call, result)
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive("FILTER_MISMATCH"), capture.values["issue_continuation_state"])
        assertEquals(JsonArray(listOf(JsonPrimitive("after"), JsonPrimitive("state"))), capture.values["validation_field_names"])
        assertFalse(capture.toString().contains("private"))
        assertFalse("issue_continuation_state" in ToolReportDiagnostics.fields(call.copy(callId = "different"), result))
        assertFalse("issue_continuation_state" in ToolReportDiagnostics.fields(call, result.copy(dispatched = true)))
        assertFalse("issue_continuation_state" in ToolReportDiagnostics.fields(call, result.copy(executionReceipt = JsonObject(receipt - "issue_continuation_rejected"))))
    }

    @Test fun searchFailuresKeepOnlyBoundCountsFromTheSameFailedSearch() {
        val call = event("""{"query":"private keywords"}""").copy(modelToolName = "web_search")
        val receipt = buildJsonObject {
            put("schema", "gptmobile.search-operation.v1")
            put("callId", call.callId)
            put("errorCode", "SEARCH_PROVIDERS_UNAVAILABLE")
            put("phase", "SEARCH_AGGREGATION")
            put("provider", "selected_search_engines")
            put("state", "FAILED")
            put("completedProviders", 0)
            put("attemptCount", 3)
            put("dispatchedAttemptCount", 1)
            put("budgetRejectedAttemptCount", 1)
        }
        val result = AgentToolResult(call.callId, ToolResultContent.Text("private provider response"), true, errorCode = "SEARCH_PROVIDERS_UNAVAILABLE", executionReceipt = receipt)
        assertEquals("SOURCE_UNAVAILABLE", ToolReportDiagnostics.reportCode(call, result))
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(call, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive(3), capture.values["search_attempt_count"])
        assertEquals(JsonPrimitive(1), capture.values["search_dispatched_attempt_count"])
        assertEquals(JsonPrimitive(1), capture.values["search_budget_rejected_attempt_count"])
        assertTrue("TASK_AND_TOOL_FAILURE_DETAILS_V1" in capture.requiredServiceFeatures())
        assertFalse(capture.toString().contains("private"))
        for (key in listOf("callId", "schema", "errorCode", "state", "phase", "provider", "completedProviders")) {
            assertNull(key, ToolReportDiagnostics.reportCode(call, result.copy(executionReceipt = JsonObject(receipt - key))))
        }
        val challenge = result.copy(
            errorCode = "CHALLENGE",
            executionReceipt = buildJsonObject {
                put("schema", "gptmobile.search-operation.v1")
                put("callId", call.callId)
                put("errorCode", "CHALLENGE")
                put("phase", "PROVIDER_RESPONSE")
                put("requestSent", true)
                put("state", "FAILED")
            }
        )
        assertEquals("SOURCE_ACCESS_BLOCKED", ToolReportDiagnostics.reportCode(call, challenge))
        assertNull(ToolReportDiagnostics.reportCode(call.copy(callId = "different"), challenge))
        assertNull(ToolReportDiagnostics.reportCode(call, challenge.copy(dispatched = false)))
    }

    @Test fun readerRedirectReasonRequiresTheSameObservedRequest() {
        val call = event("""{"url":"https://private.example/private"}""").copy(modelToolName = "read_url")
        val receipt = buildJsonObject {
            put("schema", "gptmobile.search-operation.v1")
            put("provider", "read_url")
            put("callId", call.callId)
            put("errorCode", "REDIRECT_FAILED")
            put("phase", "RETRIEVAL")
            put("state", "FAILED")
            put("requestSent", true)
            put("reader_refusal", "LOCATION_MISSING")
        }
        val result = AgentToolResult(call.callId, ToolResultContent.Text("private"), true, errorCode = "REDIRECT_FAILED", executionReceipt = receipt)
        assertEquals("SOURCE_UNAVAILABLE", ToolReportDiagnostics.reportCode(call, result))
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(call, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive("LOCATION_MISSING"), capture.values["url_reader_refusal"])
        assertTrue("READER_AND_RUN_FAILURE_DETAILS_V1" in capture.requiredServiceFeatures())
        assertFalse(capture.toString().contains("private"))
        for (key in listOf("callId", "errorCode", "provider", "state", "requestSent", "reader_refusal")) {
            val unbound = result.copy(executionReceipt = JsonObject(receipt - key))
            assertNull(key, ToolReportDiagnostics.reportCode(call, unbound))
            assertFalse(key, "url_reader_refusal" in ToolReportDiagnostics.fields(call, unbound))
        }
        assertNull(ToolReportDiagnostics.reportCode(call, result.copy(dispatched = false)))
    }

    @Test fun unknownToolKeepsTheDiscoveryStateOfThatRoundOnly() {
        val call = event("{}")
        val receipt = buildJsonObject {
            put("schema", "gptmobile.round-tool-catalog.v1")
            put("call_id", call.callId)
            put("reason_code", "UNKNOWN_TOOL")
            put("phase", "PREFLIGHT_REJECTED")
            put("dispatched", false)
            put("retryable", false)
            put("available_tool_count", 4)
            put("discovery_available", false)
        }
        val result = AgentToolResult(call.callId, ToolResultContent.Text("private"), true, dispatched = false, errorCode = "TOOL_NOT_AVAILABLE", executionReceipt = receipt)
        val fields = ToolReportDiagnostics.fields(call, result)
        val capture = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive(false), capture.values["round_catalog_discovery_available"])
        assertTrue("READER_AND_RUN_FAILURE_DETAILS_V1" in capture.requiredServiceFeatures())
        for (key in listOf("call_id", "discovery_available", "reason_code", "phase", "retryable")) {
            assertFalse(key, "round_catalog_discovery_available" in ToolReportDiagnostics.fields(call, result.copy(executionReceipt = JsonObject(receipt - key))))
        }
    }

    @Test fun rejectedDiscoveryRetainsOnlyTheSelectorShape() {
        val invalid = mapOf(
            """{"action":"search"}""" to "ACTION_WITHOUT_NAMES",
            """{"action":"search","names":null}""" to "NAMES_NULL",
            """{"names":null}""" to "NAMES_NULL",
            """{"action":"search","names":"private-tool"}""" to "NAMES_NOT_ARRAY",
            """{"action":"search","names":[]}""" to "ACTION_REQUIRES_ONE_NAME",
            """{"action":"search","names":[123]}""" to "NAMES_CONTAIN_NON_STRING",
            """{"action":false,"names":["private-tool"]}""" to "ACTION_NOT_STRING",
            """{"query":"private term","inventory_cursor":2}""" to "MIXED_INVENTORY_SELECTORS"
        )
        for ((args, expected) in invalid) {
            val call = event(args).copy(modelToolName = "discover_available_tools")
            val result = AgentToolResult(call.callId, ToolResultContent.Text("private value"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            val fields = ToolReportDiagnostics.fields(call, result)
            val capture = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
            assertEquals(JsonPrimitive(expected), capture.values["catalog_selector_state"])
            if (expected == "NAMES_NULL") {
                assertTrue(JsonPrimitive("names") in (capture.values["argument_field_names"] as? JsonArray).orEmpty())
                assertTrue(JsonPrimitive("NULL") in (capture.values["argument_field_types"] as? JsonArray).orEmpty())
            }
            assertFalse(capture.toString().contains("private"))
            assertFalse(ToolReportDiagnostics.fields(call, result.copy(dispatched = true)).containsKey("catalog_selector_state"))
        }
    }

    @Test fun githubMissingPathKeepsCallBoundReasonWithoutDisclosingTheRequestedPath() {
        val event = event("""{"action":"ranges","ranges":[{"path":"PRIVATE_FILE_PATH"}]}""").copy(modelToolName = "github_read__selected")
        val receipt = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put("call_id", event.callId)
            put("error_code", "GITHUB_PATH_NOT_FOUND")
            put("phase", "PREFLIGHT_REJECTED")
            put("dispatched", false)
            put("requires_reconciliation", false)
        }
        val result = AgentToolResult(
            event.callId,
            ToolResultContent.Text("PRIVATE_FILE_PATH"),
            true,
            errorCode = "GITHUB_PATH_NOT_FOUND",
            dispatched = false,
            executionReceipt = receipt
        )
        assertEquals("GITHUB_PATH_NOT_FOUND", ToolReportDiagnostics.reportCode(event, result))
        val fields = ToolReportDiagnostics.fields(event, result)
        assertEquals("GITHUB_PATH_NOT_FOUND", fields["tool_reason_code"])
        val diagnostics = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive("GITHUB_PATH_NOT_FOUND"), diagnostics.values["tool_reason_code"])
        assertFalse(diagnostics.toString().contains("PRIVATE_FILE_PATH"))
        val context = IssueReportContext.fromFields(
            "TOOL_RESULT",
            fields + mapOf("failed_stage" to "tool_precheck", "operation" to "github_read", "dispatched" to false)
        )
        assertEquals("GITHUB_VALIDATION_FAILED", IssueReportCodes.nativeGitHubPrecheck("GITHUB_PATH_NOT_FOUND", context)?.code)
        val wrongReceipt = result.copy(executionReceipt = JsonObject(receipt + ("call_id" to JsonPrimitive("wrong-call"))))
        assertFalse(ToolReportDiagnostics.fields(event, wrongReceipt).containsKey("tool_reason_code"))
    }

    @Test fun savedIndexPublicAliasRequiresTheExactPersistedCallAndTypedPriorCheckpointReceipt() = runBlocking {
        val event = event("""{"action":"index","map_source":"gateway"}""").copy(modelToolName = "repository_graph__selected")
        val tool = RepositoryGraphTool(event.modelToolName) {
            throw GatewayGraphOperationException(GatewayGraphAdmissionException("GRAPH_INDEX_OUTCOME_UNKNOWN", "private earlier request", "CHECK_SAVED_INDEX"), "private-host", "private name", "CHECK_SAVED_INDEX", true)
        }
        val result = tool.execute(event.callId, Json.parseToJsonElement(event.arguments).jsonObject)
        assertEquals("RECONCILIATION_REQUIRED", ToolReportDiagnostics.reportCode(event, result))
        assertEquals("GRAPH_INDEX_OUTCOME_UNKNOWN", result.errorCode)
        assertFalse(result.dispatched)
        val original = requireNotNull(result.executionReceipt)
        for (wrongEvent in listOf(
            null,
            event.copy(callId = "other-call"),
            event.copy(modelToolName = "repository_graph_other"),
            event.copy(modelToolName = "github_context"),
            event.copy(arguments = """{"action":"status","map_source":"gateway"}"""),
            event.copy(arguments = "{incomplete"),
            event.copy(arguments = " ".repeat(65_537))
        )) {
            assertNull(ToolReportDiagnostics.reportCode(wrongEvent, result))
        }
        for (key in listOf("schema", "call_id", "error_code", "phase", "dispatched", "retryable", "index_outcome_unknown", "requires_reconciliation", "repair_action", "recovery_action")) {
            assertNull(key, ToolReportDiagnostics.reportCode(event, result.copy(executionReceipt = JsonObject(original - key))))
        }
        for ((key, value) in listOf(
            "schema" to JsonPrimitive("github.dispatch.v1"),
            "call_id" to JsonPrimitive("another-call"),
            "phase" to JsonPrimitive("OUTCOME_UNKNOWN"),
            "dispatched" to JsonPrimitive(true),
            "retryable" to JsonPrimitive(true),
            "index_outcome_unknown" to JsonPrimitive(false),
            "requires_reconciliation" to JsonPrimitive(false),
            "repair_action" to JsonPrimitive("RESTORE_SELECTED_GITHUB_CONNECTION")
        )) {
            assertNull(key, ToolReportDiagnostics.reportCode(event, result.copy(executionReceipt = JsonObject(original + (key to value)))))
        }
        assertNull(ToolReportDiagnostics.reportCode(event, result.copy(dispatched = true)))
        assertNull(ToolReportDiagnostics.reportCode(event, result.copy(isError = false)))
        assertEquals("REPOSITORY_GRAPH_UNAVAILABLE", ToolReportDiagnostics.reportCode(event, result.copy(errorCode = "REPOSITORY_GRAPH_UNAVAILABLE")))
        assertNull(ToolReportDiagnostics.reportCode(event, result.copy(executionReceipt = null, content = ToolResultContent.Text("GRAPH_INDEX_OUTCOME_UNKNOWN CHECK_SAVED_INDEX requires_reconciliation=true"))))
        assertEquals(original, result.executionReceipt)
    }

    @Test fun http500IndexReportsExactBoundedGraphReasonWithoutDisclosingGatewayIdentity() = runBlocking {
        val event = event("""{"action":"index","map_source":"gateway"}""").copy(modelToolName = "repository_graph__selected")
        val attempted = "a".repeat(64)
        val tool = RepositoryGraphTool(event.modelToolName) {
            throw GatewayGraphOperationException(
                GatewayGraphHttpFailure(500),
                "private-gateway-host",
                "private gateway name",
                "CHECK_SAVED_INDEX",
                true,
                indexAttemptHash = attempted,
                currentIndexOutcomeUnknown = true
            )
        }
        val result = tool.execute(event.callId, Json.parseToJsonElement(event.arguments).jsonObject)
        val fields = ToolReportDiagnostics.fields(event, result)
        assertEquals("REPOSITORY_GRAPH_UNAVAILABLE", result.errorCode)
        assertEquals("GRAPH_HTTP_REJECTED", fields["tool_reason_code"])
        val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive("GRAPH_HTTP_REJECTED"), report.values["tool_reason_code"])
        assertEquals(JsonPrimitive(attempted), report.values["graph_index_attempt_hash"])
        assertFalse(report.toString().contains("private-gateway"))
        assertFalse(ToolReportDiagnostics.fields(event.copy(callId = "different-call"), result).containsKey("tool_reason_code"))
        val forged = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) + ("call_id" to JsonPrimitive("other-call"))))
        assertFalse(ToolReportDiagnostics.fields(event, forged).containsKey("tool_reason_code"))
        val forgedReason = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) + ("reason_code" to JsonPrimitive("UNTRUSTED_GRAPH_CAUSE"))))
        assertFalse(ToolReportDiagnostics.fields(event, forgedReason).containsKey("tool_reason_code"))
    }

    @Test fun graphTransportReportsOnlyTheCurrentGatewayFailure() = runBlocking {
        val call = event("""{"action":"metrics","map_source":"gateway"}""").copy(modelToolName = "repository_graph__selected")
        for ((cause, expected) in listOf(
            java.net.ConnectException("private endpoint") to "DISCONNECTED",
            java.net.UnknownHostException("private host") to "DNS",
            java.net.SocketTimeoutException("private URL") to "TIMEOUT",
            java.io.IOException("private detail") to "UNKNOWN",
            null to null
        )) {
            val tool = RepositoryGraphTool(call.modelToolName) {
                val unavailable = GatewayGraphUnavailableException("private gateway", listOf(GatewayGraphPrerequisite.TRANSPORT_UNAVAILABLE))
                if (cause != null) unavailable.initCause(cause)
                throw GatewayGraphOperationException(unavailable, null, null, "RESTORE_SELECTED_GATEWAY_CONNECTION", false)
            }
            val result = tool.execute(call.callId, Json.parseToJsonElement(call.arguments).jsonObject)
            val fields = ToolReportDiagnostics.fields(call, result)
            val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
            assertEquals(JsonPrimitive("GRAPH_TRANSPORT_UNAVAILABLE"), report.values["tool_reason_code"])
            assertEquals(expected?.let(::JsonPrimitive), report.values["graph_transport_kind"])
            assertEquals(expected != null, "GRAPH_TRANSPORT_EVIDENCE_V1" in report.requiredServiceFeatures())
            assertFalse(report.toString().contains("private"))
            val forged = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) + ("graph_transport_kind" to JsonPrimitive("private detail"))))
            assertFalse("graph_transport_kind" in ToolReportDiagnostics.fields(call, forged))
            for (key in listOf("schema", "call_id", "action", "reason_code", "error_code", "phase", "dispatched")) {
                val unbound = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) - key))
                val observed = ToolReportDiagnostics.fields(call, unbound)
                assertFalse(key, "graph_transport_kind" in observed)
                assertFalse(key, observed["tool_reason_code"] == "GRAPH_TRANSPORT_UNAVAILABLE")
            }
        }
    }

    @Test fun gatewayHttp500ClosedClassProjectsThroughOwnedToolReceipt() = runBlocking {
        val event = event("""{"action":"index","map_source":"gateway"}""").copy(modelToolName = "repository_graph__selected")
        val failure = GatewayGraphHttpFailure.fromResponse(500, null, buildJsonObject { put("code", "graph_internal_storage_error") })
        val tool = RepositoryGraphTool(event.modelToolName) {
            throw GatewayGraphOperationException(
                failure,
                "private-host",
                "private title",
                "CHECK_SAVED_INDEX",
                true,
                indexAttemptHash = "b".repeat(64),
                currentIndexOutcomeUnknown = true
            )
        }
        val result = tool.execute(event.callId, Json.parseToJsonElement(event.arguments).jsonObject)
        val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive("GRAPH_INTERNAL_STORAGE"), report.values["tool_reason_code"])
        assertFalse(report.toString().contains("private"))
    }

    @Test fun transportAndSavedCursorRejectionsKeepTypedReasonsWithoutArgumentValues() {
        val event = event("""{"action":"status","per_page":101,"view_cursor":"private-cursor"}""").copy(modelToolName = "github_context")
        val result = AgentToolResult(
            event.callId,
            ToolResultContent.Text("private error"),
            true,
            errorCode = "INVALID_ARGUMENTS",
            executionReceipt = buildJsonObject {
                put("reason_code", "SAVED_VIEW_CONTEXT_MISMATCH")
                put("github_transport_kind", "DNS")
                put("github_transport_wait_ms", 30000)
            }
        )
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive("SAVED_VIEW_CONTEXT_MISMATCH"), snapshot.values["tool_reason_code"])
        assertEquals(JsonPrimitive("DNS"), snapshot.values["github_transport_kind"])
        assertEquals(JsonPrimitive(101), snapshot.values["argument_page_size"])
        assertFalse(snapshot.toString().contains("private"))
    }

    @Test fun persistedCallAndTypedResolvedReceiptProvideSafeActionShapesWithoutPrivateValues() {
        val arguments = buildJsonObject {
            put("action", "create")
            put("issue_number", "private-wrong-type")
            put("private_field", "private-token")
        }
        val event = event(arguments.toString())
        val receipt = buildJsonObject {
            put(
                "resolved_arguments",
                buildJsonObject {
                    put("action", "create_issue")
                    put("title", "private-title")
                    put("body", "private-body")
                }
            )
            put("invalid_fields", JsonArray(listOf(JsonPrimitive("issue_number"), JsonPrimitive("private_field"))))
        }
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private-result"), true, errorCode = "INVALID_ARGUMENTS", executionReceipt = receipt)
        val fields = ToolReportDiagnostics.fields(event, result)
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(3, snapshot.schemaVersion)
        assertEquals(JsonPrimitive("create"), snapshot.values["native_tool_action"])
        assertEquals(JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("issue_number"))), snapshot.values["argument_field_names"])
        assertEquals(JsonArray(listOf(JsonPrimitive("STRING"), JsonPrimitive("STRING"))), snapshot.values["argument_field_types"])
        assertEquals(JsonArray(listOf(JsonPrimitive("issue_number"))), snapshot.values["validation_field_names"])
        assertTrue(snapshot.values["resolved_argument_shape_hash"] is JsonPrimitive)
        assertFalse(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private"))
        assertEquals(receipt, result.executionReceipt)
    }

    @Test fun unknownActionAndMissingOrWrongCallDoNotInventSchemaEvidence() {
        val event = event("""{"action":"private-action","private_field":"private-value"}""")
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private-result"), true, errorCode = "INVALID_ARGUMENTS")
        val fields = ToolReportDiagnostics.fields(event, result)
        assertNull(fields["native_tool_action"])
        assertNull(fields["argument_field_names"]?.let { (it as List<*>).firstOrNull { name -> name == "private_field" } })
        assertTrue(ToolReportDiagnostics.fields(null, result).isEmpty())
        assertTrue(ToolReportDiagnostics.fields(event, result.copy(callId = "other-call")).isEmpty())
    }

    @Test fun dnsCooldownProjectsWithoutTheRequestedUrl() {
        val event = event("""{"url":"https://example.com/private-source"}""").copy(modelToolName = "read_url")
        val receipt = buildJsonObject { put("schema", "gptmobile.search-operation.v1") }
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private-source"), true, errorCode = "DNS_RECOVERY", executionReceipt = receipt, dispatched = false)
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive("RECOVERY_COOLDOWN"), snapshot.values["url_reader_dns_state"])
        assertEquals(JsonPrimitive("DNS_RESOLUTION_FAILED"), snapshot.values["tool_error_code"])
        assertFalse(snapshot.toString().contains("private-source"))
        assertTrue("RECOVERY_OBSERVATIONS_V1" in snapshot.requiredServiceFeatures())
    }

    @Test fun missingUrlProjectionDistinguishesNetworkFromNegativeCache() {
        val event = event("""{"url":"https://example.com/private-source"}""").copy(modelToolName = "read_url")
        for (dispatched in listOf(true, false)) {
            val receipt = buildJsonObject {
                put("schema", "gptmobile.search-operation.v1")
                put("provider", "read_url")
                put("callId", event.callId)
                put("errorCode", "SOURCE_UNAVAILABLE")
                put("requestSent", dispatched)
                if (dispatched) put("httpStatus", 404) else put("source_availability_observation", "NEGATIVE_CACHE")
            }
            val result = AgentToolResult(event.callId, ToolResultContent.Text("private-source"), true, errorCode = "SOURCE_UNAVAILABLE", executionReceipt = receipt, dispatched = dispatched)
            val fields = ToolReportDiagnostics.fields(event, result)
            val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
            assertEquals(JsonPrimitive(if (dispatched) "HTTP_UNAVAILABLE" else "NEGATIVE_CACHE"), snapshot.values["url_reader_availability"])
            assertTrue("URL_READER_AVAILABILITY_V1" in snapshot.requiredServiceFeatures())
            assertFalse(snapshot.toString().contains("private-source"))
            assertNull(ToolReportDiagnostics.fields(event.copy(callId = "other-call"), result)["url_reader_availability"])
        }
    }

    @Test fun incompleteGitHubPaginationRetainsSafeCountsWithoutRawRecords() {
        val event = event("""{"action":"runs","page":7,"per_page":30}""")
            .copy(modelToolName = "github_ci__selected")
        val receipt = buildJsonObject {
            put("traversal_state", "INCOMPLETE")
            put(
                "pagination_progress",
                buildJsonObject {
                    put("schema", "github.pagination-progress.v1")
                    put("remote_page", 7)
                    put("remote_pages_observed", JsonArray((1..7).map(::JsonPrimitive)))
                    put("reported_total", 206)
                    put("unique_records_observed", 197)
                    put("duplicate_records_on_page", 7)
                    put("contiguous_from_first_page", true)
                    put("upstream_changed_during_traversal", false)
                    put("private_record_id", "never include this")
                }
            )
        }
        val result = AgentToolResult(
            event.callId,
            ToolResultContent.Text("private-upstream-response"),
            true,
            errorCode = "GITHUB_PAGINATION_INCOMPLETE",
            executionReceipt = receipt,
            dispatched = true
        )
        val fields = ToolReportDiagnostics.fields(event, result)
        val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(3, report.schemaVersion)
        assertEquals(JsonPrimitive(7), report.values["remote_pagination_page"])
        assertEquals(JsonPrimitive(7), report.values["remote_pagination_pages_observed"])
        assertEquals(JsonPrimitive(206), report.values["remote_pagination_reported_total"])
        assertEquals(JsonPrimitive(197), report.values["remote_pagination_unique_records"])
        assertEquals(JsonPrimitive(7), report.values["remote_pagination_duplicate_records"])
        assertEquals(JsonPrimitive(true), report.values["remote_pagination_contiguous"])
        assertEquals(JsonPrimitive(false), report.values["remote_pagination_changed"])
        assertEquals(JsonPrimitive("INCOMPLETE"), report.values["remote_pagination_reason"])
        assertFalse(report.toString().contains("never include this"))
        assertFalse(report.toString().contains("private-upstream-response"))
        assertFalse(ToolReportDiagnostics.fields(event.copy(callId = "different-call"), result).containsKey("remote_pagination_reason"))
        assertFalse(ToolReportDiagnostics.fields(event, result.copy(executionReceipt = JsonObject(receipt + ("traversal_state" to JsonPrimitive("COMPLETE"))))).containsKey("remote_pagination_reason"))
        for ((tool, action) in listOf("github_context_inspect__selected" to "commits", "github_context__selected" to "branches", "github_context_inspect__selected" to "checks", "github_pr_inspect__selected" to "list", "github_pr__selected" to "checks", "github_issue" to "list")) {
            val source = event.copy(modelToolName = tool, arguments = buildJsonObject { put("action", action) }.toString())
            val projected = ToolReportDiagnostics.fields(source, result)
            assertEquals(fields["remote_pagination_reason"], projected["remote_pagination_reason"])
            assertEquals(fields["remote_pagination_unique_records"], projected["remote_pagination_unique_records"])
        }
        assertFalse(ToolReportDiagnostics.fields(event.copy(modelToolName = "github_issue"), result).containsKey("remote_pagination_reason"))
    }

    @Test fun stageOnlyReportsTheRecordedToolFamilyAndValidatorOwnership() {
        val event = event("""{"action":"stage"}""").copy(toolName = "repository_workspace", modelToolName = "workspace__private-id")
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private result"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val fields = ToolReportDiagnostics.fields(event, result)
        val diagnostics = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive("stage"), diagnostics.values["tool_action"])
        assertEquals(JsonPrimitive("REPOSITORY_WORKSPACE"), diagnostics.values["action_only_tool_family"])
        assertEquals(JsonPrimitive("NO_RECEIPT"), diagnostics.values["action_only_validation"])
        assertTrue(IssueReportDiagnostics.ACTION_ONLY_FEATURE in diagnostics.requiredServiceFeatures())
        assertFalse(diagnostics.toString().contains("private"))
        val bound = buildJsonObject { put("argument_validation_call_id", event.callId) }
        val other = buildJsonObject { put("argument_validation_call_id", "private-other-call") }
        val unidentified = buildJsonObject { put("message", "private") }
        for ((receipt, expected) in listOf(bound to "CALL_BOUND_VALIDATOR", other to "VALIDATOR_CALL_MISMATCH", unidentified to "NO_VALIDATOR_ID")) {
            val observed = ToolReportDiagnostics.fields(event, result.copy(executionReceipt = receipt))
            assertEquals(expected, observed["action_only_validation"])
        }
        assertFalse("action_only_validation" in ToolReportDiagnostics.fields(event, result.copy(callId = "wrong")))
        assertFalse("action_only_validation" in ToolReportDiagnostics.fields(event.copy(arguments = """{"action":"stage","path":"private"}"""), result))
    }

    private fun event(arguments: String) = ToolEvent("private-event", "private-run", 1, "private-call", null, null, "github_issue", "github_issue", arguments, null, null, "FAILED")

    @Test fun genericGitHubPrecheckOnlyReportsHashesFromExactObservedExceptionReceipt() {
        val event = event("""{"action":"ranges","ranges":[{"path":"README.md"}]}""")
            .copy(modelToolName = "github_read__selected")
        val receipt = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put("call_id", event.callId)
            put("error_code", "TOOL_FAILURE")
            put("phase", "PREFLIGHT_REJECTED")
            put("dispatched", false)
            put("requires_reconciliation", false)
            put("cause_types", JsonArray(listOf(JsonPrimitive("java.lang.IllegalStateException"))))
            put("private_exception_message", "token and local-private-path")
        }
        val failure = AgentToolResult(
            event.callId,
            ToolResultContent.Text("private failure description"),
            true,
            errorCode = "TOOL_FAILURE",
            dispatched = false,
            executionReceipt = receipt
        )
        val fields = ToolReportDiagnostics.fields(event, failure)
        val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        val hashes = requireNotNull(report.values["exception_type_hashes"] as? JsonArray)
        assertEquals(1, hashes.size)
        assertEquals(
            JsonPrimitive(IssueReportContext.identifierHash("tool-exception-type", "java.lang.IllegalStateException")),
            hashes.single()
        )
        assertTrue(report.isValid())
        assertFalse(report.toString().contains("IllegalStateException"))
        assertFalse(report.toString().contains("token and local-private-path"))
        assertFalse(report.toString().contains("private failure description"))

        val withoutTypes = failure.copy(executionReceipt = JsonObject(receipt - "cause_types"))
        assertFalse("exception_type_hashes" in ToolReportDiagnostics.fields(event, withoutTypes))
        assertFalse("exception_type_hashes" in ToolReportDiagnostics.fields(event.copy(callId = "other-call"), failure))
        assertFalse("exception_type_hashes" in ToolReportDiagnostics.fields(event, failure.copy(dispatched = true)))
        assertFalse(
            "exception_type_hashes" in ToolReportDiagnostics.fields(
                event,
                failure.copy(executionReceipt = JsonObject(receipt + ("call_id" to JsonPrimitive("other-call"))))
            )
        )
    }

    @Test fun changedPrHeadReportRetainsOnlyMeasuredReadCounts() {
        val event = event("""{"action":"context","pull_number":1349}""")
            .copy(modelToolName = "github_pr__selected")
        val receipt = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put("live_read_performed", true)
            put("read_requests", 3)
            put("read_response_observations", 3)
            put("private_head_sha", "never-expose-this")
        }
        val result = AgentToolResult(
            event.callId,
            ToolResultContent.Text("private-error-body"),
            true,
            errorCode = "GITHUB_PR_HEAD_CHANGED",
            executionReceipt = receipt,
            dispatched = true
        )
        val fields = ToolReportDiagnostics.fields(event, result)
        val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive("GITHUB_PR_HEAD_CHANGED"), report.values["tool_error_code"])
        assertEquals(report, report.validated()) // Restored schema must retain the allowlisted head-race code.
        assertEquals(JsonPrimitive(true), report.values["pr_context_revision_changed"])
        assertEquals(JsonPrimitive(3), report.values["pr_context_read_requests"])
        assertEquals(JsonPrimitive(3), report.values["pr_context_read_responses"])
        assertTrue(IssueReportIncidentCapsule.GITHUB_PR_CONTEXT_RACE_FEATURE in report.requiredServiceFeatures())
        assertFalse(report.toString().contains("never-expose-this"))
        assertFalse(report.toString().contains("private-error-body"))
        val inspection = ToolReportDiagnostics.fields(event.copy(modelToolName = "github_pr_inspect__selected"), result)
        assertEquals(true, inspection["pr_context_revision_changed"])
        assertEquals(fields["pr_context_read_requests"], inspection["pr_context_read_requests"])
        assertFalse(ToolReportDiagnostics.fields(event.copy(callId = "wrong"), result).containsKey("pr_context_revision_changed"))
        assertFalse(ToolReportDiagnostics.fields(event.copy(modelToolName = "github_ci"), result).containsKey("pr_context_revision_changed"))
        assertFalse(ToolReportDiagnostics.fields(event, result.copy(dispatched = false)).containsKey("pr_context_revision_changed"))
        assertFalse(ToolReportDiagnostics.fields(event, result.copy(executionReceipt = JsonObject(receipt + ("live_read_performed" to JsonPrimitive(false))))).containsKey("pr_context_revision_changed"))
    }
}
