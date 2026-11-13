package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.agent.AgentResourceLink
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.displayResult
import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.GatewayRunDiagnosticState
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryResult
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnResult
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolEventRecorderTest {
    @Test fun actualRejectedCallPublishesOwnedContractAndShapeWithoutArgumentValues() = runBlocking {
        val reports = mutableListOf<Map<String, Any?>>()
        mockkObject(AppLogRecorder)
        try {
            every { AppLogRecorder.record("Tool", any(), "E", any(), any(), any(), any()) } answers {
                reports.add(arg(5))
                Unit
            }
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string"},"pull_number":{"type":"integer","minimum":1}},"additionalProperties":false}""").jsonObject
            val arguments = buildJsonObject {
                put("action", "context")
                put("pull_number", "private selector value")
            }
            val event = recorder.startTool("owned-tool-run", 0, "owned-tool-call", "github_pr", "github_pr__github_api", arguments, startedAt = 10)
            val rejected = dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.rejection(event.callId, event.modelToolName, arguments, schema)
            recorder.finishTool(event.eventId, rejected, 20)
            val context = IssueReportContext.fromFields("TOOL_RESULT", reports.single(), 1_000_000)
            val diagnostic = requireNotNull(context.diagnostics)
            val values = diagnostic.values
            assertEquals("TOOL", diagnostic.area)
            assertEquals(kotlinx.serialization.json.JsonPrimitive(1), values["invalid_field_count"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(0), values["missing_field_count"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(dev.chungjungsoo.gptmobile.data.agent.ToolContractFingerprint.schema(schema)), values["tool_schema_hash"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(IssueReportContext.identifierHash("tool-reason-code", "PR_SELECTOR_REQUIRED")), values["original_reason_code_hash"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(IssueReportContext.identifierHash("call", event.callId)), values["call_id_hash"])
            assertEquals(kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("WRONG_TYPE"))), values["invalid_field_classes"])
            assertEquals(false, context.dispatched)
            assertEquals(false, context.retryable)
            assertFalse(Json.encodeToString(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics.serializer(), diagnostic).contains("private"))
            val changedValues = buildJsonObject {
                put("action", "headers")
                put("pull_number", "different private value")
            }
            assertEquals(
                dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics.shapeHash(arguments),
                dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics.shapeHash(changedValues)
            )
        } finally {
            unmockkObject(AppLogRecorder)
        }
    }

    @Test fun unavailablePersistedArgumentsNeverInheritReceiptDiagnosticHashes() = runBlocking {
        data class Case(val label: String, val arguments: String, val actionAvailable: Boolean, val shapeAvailable: Boolean)

        val action = "context"
        val ordinary = buildJsonObject { put("action", action) }
        var nested = buildJsonObject { put("leaf", true) }
        repeat(14) {
            val child = nested
            nested = buildJsonObject { put("child", child) }
        }
        val cases = listOf(
            Case("ordinary", ordinary.toString(), actionAvailable = true, shapeAvailable = true),
            Case("malformed", "{", actionAvailable = false, shapeAvailable = false),
            Case(
                "oversized",
                buildJsonObject {
                    put("action", action)
                    put("padding", "x".repeat(65_536))
                }.toString(),
                actionAvailable = false,
                shapeAvailable = false
            ),
            Case(
                "depth bound",
                buildJsonObject {
                    put("action", action)
                    put("nested", nested)
                }.toString(),
                actionAvailable = true,
                shapeAvailable = false
            ),
            Case(
                "node bound",
                buildJsonObject {
                    put("action", action)
                    repeat(256) { put("field$it", true) }
                }.toString(),
                actionAvailable = true,
                shapeAvailable = false
            ),
            Case("missing action", buildJsonObject { put("query", "private value") }.toString(), actionAvailable = false, shapeAvailable = true),
            Case("nonstring action", buildJsonObject { put("action", 1) }.toString(), actionAvailable = false, shapeAvailable = true)
        )
        val foreignReceipt = buildJsonObject {
            put("diagnostic_area", "APP")
            put("diagnostic_capture_mode", "SELECTED_CAPTURE")
            put("argument_shape_hash", "a".repeat(64))
            put("tool_action_hash", "b".repeat(64))
            put("run_id_hash", "c".repeat(64))
            put("call_id_hash", "d".repeat(64))
            put("tool_name_hash", "e".repeat(64))
            put("original_error_code_hash", "f".repeat(64))
            put("original_reason_code_hash", "0".repeat(64))
        }
        val reports = mutableListOf<Map<String, Any?>>()
        mockkObject(AppLogRecorder)
        try {
            every { AppLogRecorder.record("Tool", any(), "E", any(), any(), any(), any()) } answers {
                reports.add(arg(5))
                Unit
            }
            for ((index, case) in cases.withIndex()) {
                val event = recorder.startTool("owned-tool-run", index, "owned-tool-call-$index", "github_read", "github_read", ordinary, startedAt = 10)
                dao.rows[dao.rows.indexOfFirst { it.eventId == event.eventId }] = event.copy(arguments = case.arguments)
                val saved = requireNotNull(
                    recorder.finishTool(
                        event.eventId,
                        AgentToolResult(
                            event.callId,
                            ToolResultContent.Text("private failure detail"),
                            true,
                            errorCode = "INVALID_ARGUMENTS",
                            dispatched = false,
                            executionReceipt = foreignReceipt
                        ),
                        20
                    )
                )
                val storedReceipt = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.execution(saved))
                foreignReceipt.forEach { (key, value) -> assertEquals("${case.label}: retained $key", value, storedReceipt[key]) }

                val context = IssueReportContext.fromFields("TOOL_RESULT", reports.last(), 1_000_000)
                val diagnostic = requireNotNull(context.diagnostics)
                val values = diagnostic.values
                assertEquals(case.label, "TOOL", diagnostic.area)
                assertEquals(case.label, "INCIDENT", diagnostic.captureMode)
                assertEquals(case.label, false, context.dispatched)
                assertEquals(case.label, "tool_precheck", context.stage)
                assertEquals(case.label, IssueReportContext.identifierHash("run", event.runId), values["run_id_hash"]?.jsonPrimitive?.content)
                assertEquals(case.label, IssueReportContext.identifierHash("call", event.callId), values["call_id_hash"]?.jsonPrimitive?.content)
                assertEquals(case.label, IssueReportContext.identifierHash("tool-name", event.modelToolName), values["tool_name_hash"]?.jsonPrimitive?.content)
                assertEquals(case.label, IssueReportContext.identifierHash("tool-error-code", "INVALID_ARGUMENTS"), values["original_error_code_hash"]?.jsonPrimitive?.content)
                assertFalse(case.label, values.containsKey("original_reason_code_hash"))
                assertEquals(case.label, case.actionAvailable, values.containsKey("tool_action_hash"))
                if (case.actionAvailable) {
                    assertEquals(case.label, IssueReportContext.identifierHash("tool-action", action), values["tool_action_hash"]?.jsonPrimitive?.content)
                }
                assertEquals(case.label, case.shapeAvailable, values.containsKey("argument_shape_hash"))
                if (case.shapeAvailable) {
                    assertEquals(
                        case.label,
                        dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics.shapeHash(Json.parseToJsonElement(case.arguments).jsonObject),
                        values["argument_shape_hash"]?.jsonPrimitive?.content
                    )
                }
            }
            assertEquals(cases.size, reports.size)
        } finally {
            unmockkObject(AppLogRecorder)
        }
    }

    @Test fun unavailableToolPrechecksKeepPrivateReceiptsAndPublishOnlyTheSupportedCategory() = runBlocking {
        val reports = mutableListOf<Triple<String?, Map<String, Any?>, String?>>()
        mockkObject(AppLogRecorder)
        try {
            every { AppLogRecorder.record("Tool", any(), "E", any(), any(), any(), any()) } answers {
                reports.add(Triple(arg(3), arg(5), arg(6)))
                Unit
            }
            for ((index, code) in listOf("UNKNOWN_TOOL", "UNSUPPORTED_TOOL_CONTRACT", "MCP_CONNECTION_UNAVAILABLE").withIndex()) {
                val event = recorder.startTool("unavailable-run", index, "unavailable-call-$index", "mcp", "mcp", buildJsonObject {}, startedAt = 10L)
                recorder.finishTool(
                    event.eventId,
                    AgentToolResult(
                        event.callId,
                        ToolResultContent.Text("private unavailable tool details"),
                        true,
                        errorCode = code,
                        dispatched = false,
                        executionReceipt = buildJsonObject {
                            put("retryable", false)
                            put("requires_reconciliation", false)
                            put("private_detail", "private selected connection")
                        }
                    ),
                    20L
                )
                val saved = dao.rows.last()
                val receipt = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.execution(saved))
                assertEquals(code, receipt.getValue("error_code").jsonPrimitive.content)
                assertEquals("private selected connection", receipt.getValue("private_detail").jsonPrimitive.content)
                assertEquals("private unavailable tool details", saved.recoveryResult())
                assertEquals(ToolEventStatus.FAILED, saved.status)

                val (publicCode, fields, source) = reports.last()
                assertEquals("TOOL_UNAVAILABLE", publicCode)
                assertEquals("TOOL_RESULT", source)
                val context = IssueReportContext.fromFields(requireNotNull(source), fields)
                assertEquals("tool_precheck", context.stage)
                assertEquals(false, context.dispatched)
                assertEquals(false, context.retryable)
                assertEquals(false, context.requiresReconciliation)
                assertEquals(IssueReportContext.identifierHash("run", event.runId), context.runIdHash)
                assertEquals(IssueReportContext.identifierHash("call", event.callId), context.callIdHash)
                val projected = Json.encodeToString(IssueReportContext.serializer(), context)
                assertFalse(projected.contains("private"))
                assertFalse(projected.contains(event.runId))
                assertFalse(projected.contains(event.callId))
            }
            assertEquals(3, reports.size)
        } finally {
            unmockkObject(AppLogRecorder)
        }
    }

    @Test fun ambiguousToolCodesAndUnknownOutcomesKeepTheirRecordedDispatchEvidence() = runBlocking {
        val reports = mutableListOf<Triple<String?, Map<String, Any?>, String?>>()
        val cases = listOf(
            Triple("NOT_DISPATCHED", true, null),
            Triple("DUPLICATE_ACTION", false, null),
            Triple("UNSUPPORTED_OPERATION", false, null),
            Triple("PRIVATE_PROVIDER_CODE", true, null),
            Triple("INVALID_ARGUMENTS", false, "INVALID_ARGUMENTS"),
            Triple("TOOL_OUTCOME_UNKNOWN", false, "TOOL_OUTCOME_UNKNOWN")
        )
        mockkObject(AppLogRecorder)
        try {
            every { AppLogRecorder.record("Tool", any(), "E", any(), any(), any(), any()) } answers {
                reports.add(Triple(arg(3), arg(5), arg(6)))
                Unit
            }
            for ((index, case) in cases.withIndex()) {
                val (code, dispatched, expectedCode) = case
                val event = recorder.startTool("ambiguous-run", index, "ambiguous-call-$index", "github_read", "github_read", buildJsonObject {}, startedAt = 10L)
                recorder.finishTool(event.eventId, AgentToolResult(event.callId, ToolResultContent.Text("private tool failure"), true, errorCode = code, dispatched = dispatched), 20L)

                val receipt = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.execution(dao.rows.last()))
                assertEquals(code, receipt.getValue("error_code").jsonPrimitive.content)
                assertEquals(dispatched.toString(), receipt.getValue("dispatched").jsonPrimitive.content)
                val (publicCode, fields, source) = reports.last()
                assertEquals(expectedCode, publicCode)
                assertEquals("TOOL_RESULT", source)
                val context = IssueReportContext.fromFields(requireNotNull(source), fields)
                assertEquals(dispatched, context.dispatched)
                assertEquals(if (dispatched) "tool_execution" else "tool_precheck", context.stage)
                assertEquals(null, context.retryable)
                assertEquals(null, context.resumeAllowed)
                assertEquals(if (code == "TOOL_OUTCOME_UNKNOWN") true else null, context.requiresReconciliation)
                assertEquals(null, context.remoteAcceptance)
            }
            assertEquals(cases.size, reports.size)
        } finally {
            unmockkObject(AppLogRecorder)
        }
    }

    @Test fun normalizedPrecheckIsRecoverableButUncertainPriorWriteRemainsBlocked() = runBlocking {
        val precheck = recorder.startTool("run", 0, "precheck", "github_write", "github_write", buildJsonObject {}, startedAt = 10L)
        recorder.finishTool(precheck.eventId, AgentToolResult("precheck", ToolResultContent.Text("Invalid arguments"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false), 20L)
        assertTrue(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.confirmsNoEffect(dao.rows.single()))
        val unknown = recorder.startTool("run", 1, "unknown", "github_write", "github_write", buildJsonObject {}, startedAt = 30L)
        recorder.finishTool(unknown.eventId, AgentToolResult("unknown", ToolResultContent.Text("Previous write is unreconciled"), true, errorCode = "TOOL_OUTCOME_UNKNOWN", dispatched = false), 40L)
        assertEquals(false, dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.confirmsNoEffect(dao.rows.last()))
    }

    @Test fun failedLegacyResultsRetainACorrelatedRecoveryReceiptAndCompletePayload() = runBlocking {
        val event = recorder.startTool("run-dns", 0, "dns-call", "web_search", "web_search", buildJsonObject {}, startedAt = 100L)
        recorder.finishTool(event.eventId, AgentToolResult("dns-call", ToolResultContent.Text("UnknownHostException: search.example"), true), completedAt = 125L)
        val stored = dao.rows.single()
        val receipt = requireNotNull(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.execution(stored))
        assertEquals(ToolEventStatus.FAILED, stored.status)
        assertEquals("DNS_RESOLUTION_FAILED", receipt.getValue("error_code").jsonPrimitive.content)
        assertEquals("run-dns", receipt.getValue("run_id").jsonPrimitive.content)
        assertEquals(event.eventId, receipt.getValue("event_id").jsonPrimitive.content)
        assertEquals("100", receipt.getValue("started_at").jsonPrimitive.content)
        assertEquals("125", receipt.getValue("completed_at").jsonPrimitive.content)
        assertEquals("UnknownHostException: search.example", stored.recoveryResult())
    }

    @Test fun healthMeasurementsNeverEnterPersistedTracesOrRecoveryCheckpoints() = runBlocking {
        val event = recorder.startTool("health-run", 0, "health-call", "health_connect_summary", "health_connect_summary", buildJsonObject { put("sensitive_argument", "private-987654") }, startedAt = 1L)
        val live = AgentToolResult(
            "health-call",
            ToolResultContent.Text("private-987654"),
            true,
            traceContent = ToolResultContent.Text("short display"),
            retainedContent = ToolResultContent.Text("full-private-987654")
        )
        recorder.finishTool(event.eventId, live, 2L, "private-987654 error")
        val stored = dao.rows.single()
        assertEquals("{}", stored.arguments)
        assertEquals(dev.chungjungsoo.gptmobile.data.health.HealthConnectTools.REDACTED, stored.result)
        assertEquals(ToolEventResultType.TEXT, stored.resultType)
        assertTrue(!stored.error.orEmpty().contains("987654"))
        assertEquals(ToolResultContent.Text("private-987654"), live.content)
    }

    private val dao = FakeAgentPersistenceDao()
    private val runDao = FakeAgentRunDao()
    private val recorder = ToolEventRecorder(dao, runDao, eventIdFactory = { "event-${dao.rows.size + 1}" })

    @Test
    fun gatewayCheckpointKeepsFullResearchThroughSerializationAndPersistence() = runBlocking {
        val research = "Verified evidence 😀 https://example.org/source\n".repeat(4000)
        val arguments = buildJsonObject { put("query", "Complete query".repeat(1000)) }
        val trace = ToolTraceSession("run-1", emptyList(), recorder, java.util.concurrent.atomic.AtomicInteger())
        val started = GatewayProgress(event = "tool_started", toolSource = "gateway", toolCallId = "call-web", toolName = "web_search", toolArgs = arguments)
        trace.gateway(started)
        val completed = Json.decodeFromString<GatewayProgress>(
            Json.encodeToString(GatewayProgress.serializer(), started.copy(event = "tool_completed", status = "completed", message = "Short UI summary", toolResult = research))
        )
        val state = trace.gateway(completed)
        assertEquals(research, dao.rows.single().result)
        assertEquals(arguments.toString(), dao.rows.single().arguments)
        assertEquals(research.toByteArray(Charsets.UTF_8).size, state?.metrics?.resultBytes)
    }

    @Test
    fun resumedGatewayStreamPreservesResearchEvenWhenStartWasNotReplayed() = runBlocking {
        val trace = ToolTraceSession("run-1", emptyList(), recorder, java.util.concurrent.atomic.AtomicInteger())
        trace.gateway(GatewayProgress(event = "prefetch_completed", toolSource = "gateway", toolCallId = "preload-1", toolName = "read_url", toolResult = "Full preloaded evidence", toolArgs = buildJsonObject { put("url", "https://example.org") }))
        assertEquals("Full preloaded evidence", dao.rows.single().result)
        assertEquals(ToolEventStatus.COMPLETED, dao.rows.single().status)
    }

    @Test
    fun startTool_recordsRunningEventWithStableCallIdAndCallerSequence() = runBlocking {
        val event = recorder.startTool(
            runId = "run-1",
            sequence = 7,
            callId = "call-exact",
            toolName = "search",
            modelToolName = "web__search",
            arguments = buildJsonObject { put("query", "weather") },
            connectionUid = "connection-1",
            connectionName = "Web",
            startedAt = 100L
        )

        assertEquals("event-1", event.eventId)
        assertEquals("call-exact", event.callId)
        assertEquals(7, event.sequence)
        assertEquals("search", event.toolName)
        assertEquals("web__search", event.modelToolName)
        assertEquals("""{"query":"weather"}""", event.arguments)
        assertEquals("connection-1", event.connectionUidSnapshot)
        assertEquals("Web", event.connectionNameSnapshot)
        assertEquals(ToolEventStatus.RUNNING, event.status)
        assertEquals(100L, event.startedAt)
        assertEquals(event, dao.rows.single())
    }

    @Test
    fun startTool_preservesCompleteUtf8Arguments() = runBlocking {
        val value = "a".repeat(65535) + "\uD83D\uDE00"

        val event = recorder.startTool(
            runId = "run-1",
            sequence = 0,
            callId = "call-1",
            toolName = "echo",
            modelToolName = "echo",
            arguments = buildJsonObject { put("value", value) },
            startedAt = 100L
        )

        assertEquals(buildJsonObject { put("value", value) }.toString(), event.arguments)
        assertTrue('\uFFFD' !in event.arguments)
        assertTrue(!event.arguments.last().isSurrogate())
    }

    @Test
    fun finishTool_updatesStartedEventWithTextJsonAndResourceLinks() = runBlocking {
        val text = recorder.startTool("run-1", 0, "call-text", "echo", "echo", buildJsonObject {}, startedAt = 100L)
        recorder.finishTool(text.eventId, AgentToolResult("call-text", ToolResultContent.Text("ok"), isError = false), completedAt = 110L)

        val json = recorder.startTool("run-1", 1, "call-json", "lookup", "lookup", buildJsonObject {}, startedAt = 120L)
        recorder.finishTool(json.eventId, AgentToolResult("call-json", ToolResultContent.Json(buildJsonObject { put("answer", 42) }), isError = false), completedAt = 130L)

        val links = recorder.startTool("run-1", 2, "call-links", "files", "files", buildJsonObject {}, startedAt = 140L)
        recorder.finishTool(
            links.eventId,
            AgentToolResult(
                "call-links",
                ToolResultContent.ResourceLinks(
                    listOf(
                        AgentResourceLink(uri = "file://one", name = "One", mimeType = "text/plain"),
                        AgentResourceLink(uri = "file://two")
                    )
                ),
                isError = false
            ),
            completedAt = 150L
        )

        assertEquals(
            listOf(ToolEventResultType.TEXT, ToolEventResultType.JSON, ToolEventResultType.RESOURCE_LINKS),
            dao.rows.map { it.resultType }
        )
        assertEquals("ok", dao.rows[0].result)
        assertEquals("""{"answer":42}""", dao.rows[1].result)
        val serializedLinks = Json.parseToJsonElement(dao.rows[2].result!!).jsonArray[0].jsonObject
        assertEquals("file://one", serializedLinks.getValue("uri").jsonPrimitive.content)
        assertEquals("One", serializedLinks.getValue("name").jsonPrimitive.content)
        assertEquals("text/plain", serializedLinks.getValue("mimeType").jsonPrimitive.content)
        assertEquals(setOf("uri", "name", "mimeType"), Json.parseToJsonElement(dao.rows[2].result!!).jsonArray[1].jsonObject.keys)
        assertEquals(listOf(ToolEventStatus.COMPLETED, ToolEventStatus.COMPLETED, ToolEventStatus.COMPLETED), dao.rows.map { it.status })
        assertEquals(listOf("call-text", "call-json", "call-links"), dao.rows.map { it.callId })
    }

    @Test
    fun finishTool_successfulDelegationMarkerOverridesContradictoryOuterError() = runBlocking {
        val event = recorder.startTool(
            "run-1",
            0,
            "delegate-call",
            "delegate_to_model",
            "delegate_to_model",
            buildJsonObject {},
            startedAt = 100L
        )

        recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult(
                "delegate-call",
                ToolResultContent.Text("<!-- delegation:local -->\nReviewed delegated evidence"),
                isError = true
            ),
            completedAt = 110L,
            error = "incorrect outer failure"
        )

        val stored = dao.rows.single()
        assertEquals(ToolEventStatus.COMPLETED, stored.status)
        assertEquals(false, stored.isError)
        assertEquals(null, stored.error)
        assertTrue(stored.result.orEmpty().contains("Reviewed delegated evidence"))
    }

    @Test
    fun finishTool_preservesCompleteResultAndMarksErrorsFailed() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-error", "echo", "echo", buildJsonObject {}, startedAt = 100L)

        recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult("call-error", ToolResultContent.Text("a".repeat(70 * 1024)), isError = true),
            completedAt = 110L,
            error = "boom"
        )

        val stored = dao.rows.single()
        assertEquals(ToolEventStatus.FAILED, stored.status)
        assertEquals(true, stored.isError)
        assertEquals("boom", stored.error)
        assertEquals(110L, stored.completedAt)
        assertTrue(stored.result!!.toByteArray(Charsets.UTF_8).size >= 70 * 1024)
    }

    @Test
    fun finishTool_preservesFullContentAlongsideItsDisplayTrace() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-trace", "image", "image", buildJsonObject {}, startedAt = 100L)

        recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult(
                callId = "call-trace",
                content = ToolResultContent.Text("safe model result"),
                isError = false,
                traceContent = ToolResultContent.Text("image/png omitted from model context")
            ),
            completedAt = 110L
        )

        assertEquals("image/png omitted from model context", dao.rows.single().displayResult())
        assertEquals("safe model result", dao.rows.single().recoveryResult())
    }

    @Test
    fun finishTool_callIdMismatchReturnsNullAndDoesNotWrite() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-expected", "echo", "echo", buildJsonObject {}, startedAt = 100L)

        val finished = recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult("call-other", ToolResultContent.Text("wrong"), isError = false),
            completedAt = 110L
        )

        assertEquals(null, finished)
        assertEquals(event, dao.rows.single())
    }

    @Test
    fun finishTool_cancelBeforeFinishReturnsNullAndPreservesCanceledRow() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-1", "echo", "echo", buildJsonObject {}, startedAt = 100L)
        recorder.cancelRun("run-1", completedAt = 105L)
        val canceled = dao.rows.single()

        val finished = recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult("call-1", ToolResultContent.Text("late"), isError = false),
            completedAt = 110L
        )

        assertEquals(null, finished)
        assertEquals(canceled, dao.rows.single())
    }

    @Test
    fun finishTool_duplicateFinishReturnsNullAndPreservesFirstTerminalRow() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-1", "echo", "echo", buildJsonObject {}, startedAt = 100L)
        val first = recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult("call-1", ToolResultContent.Text("first"), isError = false),
            completedAt = 110L
        )

        val second = recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult("call-1", ToolResultContent.Text("second"), isError = false),
            completedAt = 120L
        )

        assertEquals(null, second)
        assertEquals(first, dao.rows.single())
    }

    @Test
    fun finishTool_preservesOversizedJsonForRecovery() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-json", "lookup", "lookup", buildJsonObject {}, startedAt = 100L)

        recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult(
                "call-json",
                ToolResultContent.Json(buildJsonObject { put("value", "a".repeat(70 * 1024)) }),
                isError = false
            ),
            completedAt = 110L
        )

        val stored = dao.rows.single()
        assertEquals(ToolEventResultType.JSON, stored.resultType)
        assertTrue(stored.result!!.toByteArray(Charsets.UTF_8).size >= 70 * 1024)
    }

    @Test
    fun finishTool_preservesOversizedResourceLinksForRecovery() = runBlocking {
        val event = recorder.startTool("run-1", 0, "call-links", "files", "files", buildJsonObject {}, startedAt = 100L)

        recorder.finishTool(
            eventId = event.eventId,
            result = AgentToolResult(
                "call-links",
                ToolResultContent.ResourceLinks(
                    listOf(AgentResourceLink(uri = "file://${"a".repeat(70 * 1024)}"))
                ),
                isError = false
            ),
            completedAt = 110L
        )

        val stored = dao.rows.single()
        assertEquals(ToolEventResultType.RESOURCE_LINKS, stored.resultType)
        assertTrue(stored.result!!.toByteArray(Charsets.UTF_8).size >= 70 * 1024)
    }

    @Test
    fun cancelRun_marksOnlyActiveEventsCanceled() = runBlocking {
        dao.rows += event("done", "run-1", 0, ToolEventStatus.COMPLETED, completedAt = 90L)
        dao.rows += event("running", "run-1", 1, ToolEventStatus.RUNNING)
        dao.rows += event("pending", "run-1", 2, ToolEventStatus.PENDING)
        dao.rows += event("other", "run-2", 0, ToolEventStatus.RUNNING)

        recorder.cancelRun("run-1", completedAt = 200L)

        assertEquals(ToolEventStatus.COMPLETED, dao.rows[0].status)
        assertEquals(90L, dao.rows[0].completedAt)
        assertEquals(ToolEventStatus.CANCELED, dao.rows[1].status)
        assertEquals(200L, dao.rows[1].completedAt)
        assertEquals(ToolEventStatus.CANCELED, dao.rows[2].status)
        assertEquals(ToolEventStatus.RUNNING, dao.rows[3].status)
    }

    @Test
    fun getEventsReturnsOrderedRowsAndEmptySetDoesNotQueryDao() = runBlocking {
        dao.rows += event("event-3", "run-2", 1, ToolEventStatus.COMPLETED)
        dao.rows += event("event-1", "run-1", 2, ToolEventStatus.COMPLETED)
        dao.rows += event("event-2", "run-1", 1, ToolEventStatus.COMPLETED)

        assertEquals(listOf("event-2", "event-1"), recorder.getEvents("run-1").map { it.eventId })
        assertEquals(listOf("event-2", "event-1", "event-3"), recorder.getEvents(setOf("run-2", "run-1")).map { it.eventId })

        dao.bulkQueryCount = 0
        assertEquals(emptyList<ToolEvent>(), recorder.getEvents(emptySet()))
        assertEquals(0, dao.bulkQueryCount)
    }

    @Test
    fun observeChat_delegatesPositiveChatIdsToDao() = runBlocking {
        val orderedEvents = listOf(
            event("event-2", "run-a", 2, ToolEventStatus.RUNNING),
            event("event-1", "run-b", 1, ToolEventStatus.COMPLETED)
        )
        dao.observedToolEvents.value = orderedEvents

        assertEquals(orderedEvents.map { it.eventId }, recorder.observeChat(42).first().map { it.eventId })
        assertEquals(listOf(42), dao.observedChatIds)
    }

    @Test
    fun observeChat_zeroChatIdEmitsEmptyListWithoutQueryingDao() = runBlocking {
        dao.observedToolEvents.value = listOf(event("event-1", "run-1", 0, ToolEventStatus.RUNNING))

        assertEquals(emptyList<ToolEvent>(), recorder.observeChat(0).first())
        assertEquals(emptyList<Int>(), dao.observedChatIds)
    }

    @Test
    fun observeChat_negativeChatIdEmitsEmptyListWithoutQueryingDao() = runBlocking {
        dao.observedToolEvents.value = listOf(event("event-1", "run-1", 0, ToolEventStatus.RUNNING))

        assertEquals(emptyList<ToolEvent>(), recorder.observeChat(-1).first())
        assertEquals(emptyList<Int>(), dao.observedChatIds)
    }

    private fun event(
        eventId: String,
        runId: String,
        sequence: Int,
        status: String,
        completedAt: Long? = null
    ) = ToolEvent(
        eventId = eventId,
        runId = runId,
        sequence = sequence,
        callId = "call-$eventId",
        connectionUidSnapshot = null,
        connectionNameSnapshot = null,
        toolName = "tool",
        modelToolName = "tool",
        arguments = "{}",
        result = null,
        resultType = null,
        status = status,
        completedAt = completedAt
    )
}

private class FakeAgentRunDao :
    AgentRunDao,
    UnusedPolicySnapshotAccess {
    override suspend fun gatewayDiagnosticStates(baseUrl: String, profileUid: String): List<GatewayRunDiagnosticState> = emptyList()
    override suspend fun recordGatewayCancellation(runId: String, jobId: String, receipt: String): Int = 0
    override suspend fun finishGatewayRecovery(runId: String, status: String, completedAt: Long, terminalError: String?): Int = 0
    override suspend fun replaceMeasuredUsage(runId: String, inputTokens: Int?, outputTokens: Int?, totalTokens: Int?): Int = 0
    override suspend fun rawGetById(runId: String): AgentRun? = getById(runId)
    override suspend fun rawGetByChatId(chatId: Int): List<AgentRun> = getByChatId(chatId)
    override suspend fun sessionAuditRuns(chatId: Int, currentRunId: String): List<AgentRun> = emptyList()
    override suspend fun rawCombinedTerminalOwners(): List<AgentRun> = combinedTerminalOwners()
    override suspend fun rawGetRecoverableGatewayRuns(): List<AgentRun> = getRecoverableGatewayRuns()
    override fun rawObserveByChatId(chatId: Int): Flow<List<AgentRun>> = observeByChatId(chatId)
    override fun rawObserveActive(): Flow<List<AgentRun>> = observeActive()
    override fun rawObserveRecent(limit: Int): Flow<List<AgentRun>> = observeRecent(limit)

    override fun observeHomeActivity(limit: Int): Flow<List<dev.chungjungsoo.gptmobile.data.model.SettingsHomeActivity>> = MutableStateFlow(emptyList())
    override fun observeActive(): Flow<List<AgentRun>> = MutableStateFlow(emptyList())
    override fun observeUnreportedOutputLengths(limit: Int): Flow<List<dev.chungjungsoo.gptmobile.data.database.dao.RunOutputLength>> = MutableStateFlow(emptyList())

    var advancedSequence: Pair<String, Int>? = null

    override suspend fun upsert(run: AgentRun) = Unit
    override suspend fun getById(runId: String): AgentRun? = null
    override suspend fun getByChatId(chatId: Int): List<AgentRun> = emptyList()
    override fun observeByChatId(chatId: Int): Flow<List<AgentRun>> = MutableStateFlow(emptyList())
    override fun observeRecent(limit: Int): Flow<List<AgentRun>> = MutableStateFlow(emptyList())
    override suspend fun updateStatus(
        runId: String,
        status: String,
        startedAt: Long?,
        completedAt: Long?,
        terminalError: String?
    ) = Unit
    override suspend fun markRunning(runId: String, startedAt: Long): Int = 0
    override suspend fun finishRunning(runId: String, status: String, completedAt: Long, terminalError: String?): Int = 0
    override suspend fun finishQueued(runId: String, status: String, completedAt: Long, terminalError: String?): Int = 0
    override suspend fun finishActive(runId: String, status: String, completedAt: Long, terminalError: String?): Int = 0
    override suspend fun interruptActiveRuns(completedAt: Long): Int = 0
    override suspend fun bindGatewayJob(runId: String, jobId: String, baseUrl: String): Int = 0
    override suspend fun advanceGatewaySequence(runId: String, sequence: Int): Int {
        advancedSequence = runId to sequence
        return 1
    }
    override suspend fun updateUsage(
        runId: String,
        inputTokens: Int?,
        outputTokens: Int?,
        totalTokens: Int?
    ): Int = 0
    override suspend fun getRecoverableGatewayRuns(): List<AgentRun> = emptyList()
    override suspend fun combinedTerminalOwners(): List<AgentRun> = emptyList()
}

private class FakeAgentPersistenceDao :
    AgentPersistenceDao,
    UnusedPolicySnapshotAccess {
    override suspend fun combinedFinalMessage(chatId: Int, userId: Int): dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalMessage? = null
    override suspend fun insertCombinedFinalMessage(row: dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalMessage) {
        error("Not used in tool recorder tests")
    }
    override suspend fun providerWaits(): List<dev.chungjungsoo.gptmobile.data.agent.ProviderWait> = emptyList()
    override fun observeProviderWaitDeadlines(chatId: Int): Flow<List<dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override fun observeProviderWaitRows(runIds: List<String>): Flow<List<dev.chungjungsoo.gptmobile.data.agent.ProviderWait>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun rawProviderWait(runId: String): dev.chungjungsoo.gptmobile.data.agent.ProviderWait? = null
    override suspend fun terminalProviderWaitIds(): List<String> = emptyList()
    override suspend fun markClaimedProviderWaiting(runId: String, fence: Long): Int = 0
    override suspend fun saveProviderWait(row: dev.chungjungsoo.gptmobile.data.agent.ProviderWait) {
        error("Not used in tool recorder tests")
    }
    override suspend fun markProviderWaiting(runId: String, fence: Long): Int = 0
    override suspend fun markRunningProviderWaiting(runId: String, fence: Long): Int = 0
    override suspend fun claimProviderWait(runId: String, fence: Long): Int = 0
    override suspend fun blockProviderWait(runId: String, reason: String, completedAt: Long): Int = 0

    override suspend fun markProviderResumeRunning(runId: String, fence: Long, startedAt: Long): Int = 0

    override suspend fun rawSynthesisRunsForTurn(chatId: Int, userId: Int): List<AgentRun> = synthesisRunsForTurn(chatId, userId)
    override suspend fun rawGetCompletedRuns(chatId: Int): List<AgentRun> = getCompletedRuns(chatId)
    override suspend fun rawGetIncompleteRuns(chatId: Int, userMessageIds: List<Int>): List<AgentRun> = getIncompleteRuns(chatId, userMessageIds)
    override suspend fun sessionAuditToolFailures(runIds: List<String>): List<dev.chungjungsoo.gptmobile.data.github.GitHubSessionToolFailure> = emptyList()
    override suspend fun rawRecoveryRun(runId: String): AgentRun? = recoveryRun(runId)

    override suspend fun synthesisRunsForTurn(chatId: Int, userId: Int): List<AgentRun> = unused()
    override suspend fun combinedDeliveryCheckpoint(runId: String, revisionId: String): dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint? = unused()
    override suspend fun rawCombinedDeliveryCheckpoint(runId: String, revisionId: String): dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint? = unused()
    override suspend fun upsertCombinedDeliveryCheckpoint(row: dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint) = unused<Unit>()
    override suspend fun deleteCombinedModels(chatId: Int, profileUids: List<String>) = unused<Unit>()
    override suspend fun archiveCombinedSegments(runId: String, archivedRunId: String) = unused<Unit>()
    override suspend fun advanceCombinedFence(runId: String, expectedFence: Long, nextFence: Long): Int = unused()
    override suspend fun publication(key: String): dev.chungjungsoo.gptmobile.data.chat.ReplyPublication? = null
    override suspend fun nextPublicationSequence(): Long = 1
    override suspend fun savePublication(row: dev.chungjungsoo.gptmobile.data.chat.ReplyPublication) = Unit

    override suspend fun answerSegmentsForChat(chatId: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment> = emptyList()
    override suspend fun answerSegments(runId: String): List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment> = emptyList()
    override suspend fun rawAnswerSegments(runId: String): List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment> = emptyList()
    override suspend fun insertAnswerSegments(segments: List<dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment>) = Unit

    override suspend fun combinedDocumentOwner(messageId: Int): MessageV2? = unused()
    override suspend fun insertCombinedDurableBlocks(blocks: List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock>) = unused<Unit>()
    override suspend fun combinedDurableBlockPage(runId: String, payloadId: String, first: Int, count: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock> = unused()
    override suspend fun combinedSharedBlocks(runId: String, payloadIds: List<String>): List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock> = unused()
    override suspend fun insertCombinedDeliveryRecords(records: List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryRecord>) = unused<Unit>()
    override suspend fun combinedDeliveryRecord(runId: String, revisionId: String, fence: Long, chainDigest: String): dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryRecord? = unused()
    override suspend fun insertCombinedFinalManifest(manifest: dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalManifest) = unused<Unit>()
    override suspend fun combinedFinalManifest(id: String): dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalManifest? = unused()
    override suspend fun insertCombinedFinalBlockIndexes(blocks: List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalBlockIndex>) = unused<Unit>()
    override suspend fun combinedFinalBlockIndexPage(manifestId: String, first: Int, count: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalBlockIndex> = unused()
    override suspend fun markCombinedManifestCommitted(id: String, fence: Long, committedAt: Long): Int = unused()
    override suspend fun combinedFinalManifestsForChat(chatId: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalManifest> = unused()
    override suspend fun combinedDurableBlocksForChat(chatId: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock> = unused()
    override suspend fun combinedFinalBlockIndexesForChat(chatId: Int): List<dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalBlockIndex> = unused()
    override suspend fun personalDataToolSources(chatId: Int): List<dev.chungjungsoo.gptmobile.data.permissions.PersonalDataToolSource> = unused()

    override suspend fun removeArchiveSearchIndex(chatId: Int) = unused<Unit>()
    override suspend fun recoveryRun(runId: String): AgentRun? = unused()
    override suspend fun rawRecoveryMessage(messageId: Int): MessageV2? = unused()
    override suspend fun pendingPrompt(id: String): dev.chungjungsoo.gptmobile.data.queue.PendingPrompt? = unused()
    override suspend fun consumePrompt(id: String, messageId: Int): Int = unused()
    override suspend fun firstPendingId(chatId: Int): String? = unused()
    override suspend fun activeRunCount(chatId: Int): Int = unused()
    override suspend fun rawLatestAssistantMessages(chatId: Int): List<MessageV2> = unused()

    val rows = mutableListOf<ToolEvent>()
    val observedToolEvents = MutableStateFlow(emptyList<ToolEvent>())
    val observedChatIds = mutableListOf<Int>()
    var bulkQueryCount = 0

    override fun rawObserveRecentToolEvents(limit: Int): Flow<List<ToolEvent>> = observedToolEvents.map { events ->
        events.sortedWith(compareByDescending<ToolEvent> { it.completedAt ?: it.startedAt ?: 0L }.thenByDescending { it.sequence }).take(limit)
    }

    override suspend fun insertChatRoom(chatRoom: ChatRoomV2): Long = unused()
    override suspend fun updateChatRoom(chatRoom: ChatRoomV2) = unused<Unit>()
    override suspend fun insertMessage(message: MessageV2): Long = unused()
    override suspend fun insertRun(run: AgentRun) = unused<Unit>()
    override suspend fun insertToolEvent(event: ToolEvent) {
        rows += event
    }
    override suspend fun deleteMessages(messages: List<MessageV2>) = unused<Unit>()
    override suspend fun upsertModels(models: List<ChatPlatformModelV2>) = unused<Unit>()
    override suspend fun getChatRoom(chatId: Int): ChatRoomV2? = unused()
    override suspend fun rawGetMessages(chatId: Int): List<MessageV2> = unused()
    override suspend fun getModels(chatId: Int): List<ChatPlatformModelV2> = unused()
    override suspend fun getCompletedRuns(chatId: Int): List<AgentRun> = unused()
    override suspend fun getIncompleteRuns(chatId: Int, userMessageIds: List<Int>): List<AgentRun> = unused()
    override suspend fun rawGetToolEvents(runIds: List<String>): List<ToolEvent> {
        bulkQueryCount++
        return rows
            .filter { it.runId in runIds }
            .sortedWith(compareBy(ToolEvent::runId, ToolEvent::sequence))
    }
    override suspend fun persistAgentTurn(request: PersistAgentTurnRequest): PersistAgentTurnResult = unused()
    override suspend fun saveChatSnapshot(chatRoom: ChatRoomV2, messages: List<MessageV2>, chatPlatformModels: Map<String, String>) = unused<Unit>()
    override suspend fun persistAgentRetry(request: PersistAgentRetryRequest): PersistAgentRetryResult = unused()
    override suspend fun duplicateChatWithHistory(sourceChatId: Int, title: String, timestamp: Long, editedUser: MessageV2?): ChatRoomV2 = unused()
    override suspend fun archiveRunIds(chatId: Int): List<String> = emptyList()
    override suspend fun updateArchiveMessage(id: Int, content: String, thoughts: String, revisions: String, timeline: String, sources: String) = Unit
    override suspend fun updateArchiveTool(id: String, arguments: String, result: String?, error: String?) = Unit
    override suspend fun updateArchiveFlag(chatId: Int, archived: Boolean) = Unit
    override suspend fun updateMessage(message: MessageV2) = unused<Unit>()
    override suspend fun rawUpdateMessage(message: MessageV2) = unused<Unit>()
    override suspend fun updateRunStatus(
        runId: String,
        status: String,
        startedAt: Long?,
        completedAt: Long?,
        terminalError: String?
    ) = unused<Unit>()

    override suspend fun rawGetToolEventsForRun(runId: String): List<ToolEvent> = rows
        .filter { it.runId == runId }
        .sortedBy { it.sequence }

    override suspend fun rawGetToolEventById(eventId: String): ToolEvent? = rows.firstOrNull { it.eventId == eventId }

    override suspend fun rawGetToolEventByCall(runId: String, callId: String): ToolEvent? = rows
        .filter { it.runId == runId && it.callId == callId }
        .sortedBy { it.startedAt }
        .firstOrNull()

    override fun rawObserveToolEventsForChat(chatId: Int): Flow<List<ToolEvent>> {
        observedChatIds += chatId
        return observedToolEvents
    }

    override suspend fun finishToolEvent(
        eventId: String,
        callId: String,
        result: String,
        resultType: String,
        status: String,
        isError: Boolean,
        completedAt: Long,
        error: String?
    ): Int {
        val index = rows.indexOfFirst {
            it.eventId == eventId &&
                it.callId == callId &&
                it.status in listOf(ToolEventStatus.PENDING, ToolEventStatus.RUNNING)
        }
        if (index < 0) return 0
        rows[index] = rows[index].copy(
            result = result,
            resultType = resultType,
            status = status,
            isError = isError,
            completedAt = completedAt,
            error = error
        )
        return 1
    }

    override suspend fun cancelActiveToolEvents(runId: String, completedAt: Long) {
        rows.replaceAll { event ->
            if (event.runId == runId && event.status in listOf(ToolEventStatus.PENDING, ToolEventStatus.RUNNING)) {
                event.copy(status = ToolEventStatus.CANCELED, completedAt = completedAt)
            } else {
                event
            }
        }
    }

    override suspend fun cancelInterruptedToolEvents(completedAt: Long) = Unit

    private fun <T> unused(): T = error("unused")
}

private fun Char.isSurrogate(): Boolean = this in '\uD800'..'\uDFFF'

/** This fixture tests event recording; policy storage is covered by real Room contract tests. */
private interface UnusedPolicySnapshotAccess : dev.chungjungsoo.gptmobile.data.database.dao.RunPolicySnapshotAccess {
    override suspend fun policyChatTemporary(chatId: Int): Boolean? = false
    override suspend fun policyRows(chatId: Int, digests: List<String>): List<dev.chungjungsoo.gptmobile.data.database.entity.RunPolicySnapshot> = emptyList()
    override suspend fun policyRowsForRuns(runIds: List<String>): List<dev.chungjungsoo.gptmobile.data.database.entity.RunPolicySnapshot> = emptyList()
    override suspend fun policyRefsForRuns(runIds: List<String>): List<dev.chungjungsoo.gptmobile.data.database.entity.AgentRunPolicyRef> = emptyList()
    override suspend fun insertPolicyRows(rows: List<dev.chungjungsoo.gptmobile.data.database.entity.RunPolicySnapshot>) = Unit
    override suspend fun insertPolicyRefs(refs: List<dev.chungjungsoo.gptmobile.data.database.entity.AgentRunPolicyRef>) = Unit
    override suspend fun removePolicyRefs(runId: String) = Unit
    override suspend fun removeUnusedPolicies(chatId: Int) = Unit
    override suspend fun rawInsertPolicyRun(run: AgentRun) = Unit
    override suspend fun rawUpsertPolicyRun(run: AgentRun) = Unit
}
