package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecoveryReportSubmissionTest {
    private val origin = "https://reports.example.invalid"
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained report", description = "The observed failure")

    @Test fun newRecoveryEvidenceNegotiatesSupportBeforeSendingUnchangedApprovedBytes() {
        val feature = IssueReportIncidentCapsule.CONVERSATION_RECOVERY_FEATURE
        val contexts = listOf(
            mapOf("diagnostic_area" to "PROVIDER", "verification_phase" to "CLAIM_EXTRACTION", "incident_capture_site" to "GITHUB_VERIFICATION_CATCH"),
            mapOf("diagnostic_area" to "PROVIDER", "continuation_cause" to "NO_SAVED_TASK", "next_action" to "SEND_ORIGINAL_REQUEST"),
            mapOf("diagnostic_area" to "PROVIDER", "checkpoint_mismatch_reason" to "DIGEST_CHANGED", "observed_checkpoint_state" to "TOOL_RESULTS_READY"),
            mapOf("diagnostic_area" to "TOOL", "issue_continuation_state" to "MISSING_AFTER", "validation_field_names" to listOf("after")),
            mapOf("diagnostic_area" to "ACCEPTANCE", "acceptance_advertised_tool_count" to 0),
            mapOf("diagnostic_area" to "TOOL", "catalog_selector_state" to "NAMES_NULL", "argument_field_names" to listOf("names", "inventory_cursor", "inventory_version"))
        ).map { fields -> report.copy(context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)) }
        val variants = contexts + listOf(
            report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = "GITHUB_VERIFICATION_INTERNAL_ERROR"),
            report.copy(events = listOf("-1ms E chat PROVIDER_WAIT_CHECKPOINT_CHANGED"))
        )
        for (approved in variants) {
            val payload = approved.approvedPayload()
            val required = approved.context?.diagnostics?.requiredServiceFeatures().orEmpty() + feature
            var compatible = false
            var dispatched = 0
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray((if (compatible) required else required - feature).map(::JsonPrimitive)))
                    }.toString()
                } else {
                    posted += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    buildJsonObject {
                        put("reportId", approved.reportId)
                        put("number", 81)
                    }.toString()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val submission = IssueReportSubmission(origin, client)
            val deferred = submission.send(approved.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", deferred.code)
            assertFalse(deferred.automaticRetry)
            assertEquals(0, dispatched)
            assertEquals(emptyList<String>(), posted)
            compatible = true
            assertEquals(IssueReportSubmission.Confirmed(81), submission.send(approved.reportId, payload) { dispatched++ })
            assertEquals(1, dispatched)
            assertEquals(listOf(payload), posted)
        }
    }

    @Test fun newRecoveryReportEvidenceDefersOldReportersBeforePostingApprovedBytes() {
        val recoveryFeature = IssueReportDiagnostics.RECOVERY_REPORT_FEATURE
        val missingToolFeature = IssueReportDiagnostics.MISSING_TOOL_IDENTITY_FEATURE
        val anr = IssueReportContext.fromFields(
            "PROCESS_EXIT",
            mapOf("diagnostic_area" to "APP", "anr_trace_state" to "MAIN_THREAD_CAPTURED", "anr_frame_count" to 3),
            1_000L
        )
        val anrOmitted = anr.withDiagnostics(
            requireNotNull(anr.diagnostics).copy(values = emptyMap(), omittedFields = listOf("anr_trace_state", "anr_frame_count"))
        )
        val missingTool = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf("diagnostic_area" to "TOOL", "missing_tool_hash" to "a".repeat(64)),
            1_000L
        )
        val missingToolOmitted = missingTool.withDiagnostics(
            requireNotNull(missingTool.diagnostics).copy(values = emptyMap(), omittedFields = listOf("missing_tool_hash"))
        )
        val savedPageContexts = listOf("SAVED_PAGE_BODY_UNAVAILABLE", "SAVED_PAGE_DELIVERY_MISMATCH").map { state ->
            IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "INCIDENT", "replay_budget_state" to state), 1_000L)
        }
        val legacy = IssueReportContext.fromFields("PROCESS_EXIT", mapOf("diagnostic_area" to "APP"), 1_000L)
        val variants: List<Pair<IssueReportContext, String?>> = listOf(
            anr to recoveryFeature,
            anrOmitted to recoveryFeature,
            missingTool to missingToolFeature,
            missingToolOmitted to missingToolFeature
        ) + savedPageContexts.map { it to recoveryFeature } + listOf(legacy to null)
        for ((context, feature) in variants) {
            val requiresNewFeature = feature != null
            val diagnostics = requireNotNull(context.diagnostics)
            assertTrue(diagnostics.isValid())
            assertEquals(if (diagnostics.area == "INCIDENT") 3 else 1, diagnostics.schemaVersion)
            val required = diagnostics.requiredServiceFeatures()
            assertEquals(requiresNewFeature, feature != null && feature in required)
            val approved = report.copy(context = context)
            val payload = approved.approvedPayload()
            val approvedBytes = payload.encodeToByteArray()
            val methods = mutableListOf<String>()
            val posted = mutableListOf<ByteArray>()
            var compatible = false
            var dispatched = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                val body = when (request.method) {
                    "GET" -> {
                        val suffix = if (requiresNewFeature) "?capabilities=extended" else ""
                        assertEquals("$origin/health/ready$suffix", request.url.toString())
                        val advertised = if (compatible || feature == null) required else required - feature
                        val features = if (feature == missingToolFeature) advertised + recoveryFeature else advertised
                        if (feature == missingToolFeature) assertTrue(recoveryFeature in features)
                        buildJsonObject {
                            put("service", "gpt-mobile-issue-reporter")
                            put("version", 2)
                            put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                            put("reportDiagnosticFeatures", JsonArray(features.map(::JsonPrimitive)))
                        }.toString()
                    }
                    "POST" -> {
                        assertEquals("$origin/v1/issue-reports", request.url.toString())
                        assertEquals(1, dispatched)
                        posted += Buffer().also { requireNotNull(request.body).writeTo(it) }.readByteArray()
                        buildJsonObject {
                            put("reportId", approved.reportId)
                            put("number", 81)
                        }.toString()
                    }
                    else -> error("Unexpected report transport method: ${request.method}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val submission = IssueReportSubmission(origin, client)
            if (requiresNewFeature) {
                assertEquals(
                    IssueReportSubmission.Failure("REPORT_SERVICE_UPDATE_REQUIRED", retryable = true, automaticRetry = false),
                    submission.send(approved.reportId, payload) { dispatched++ }
                )
                assertEquals(listOf("GET"), methods)
                assertEquals(0, dispatched)
                assertTrue(posted.isEmpty())
                assertArrayEquals(approvedBytes, payload.encodeToByteArray())
                compatible = true
                assertEquals(IssueReportSubmission.Confirmed(81), submission.send(approved.reportId, payload) { dispatched++ })
                assertEquals(listOf("GET", "GET", "POST"), methods)
            } else {
                assertFalse(recoveryFeature in required)
                assertFalse(missingToolFeature in required)
                assertEquals(IssueReportSubmission.Confirmed(81), submission.send(approved.reportId, payload) { dispatched++ })
                assertEquals(listOf("GET", "POST"), methods)
            }
            assertEquals(1, dispatched)
            assertEquals(1, posted.size)
            assertArrayEquals(approvedBytes, posted.single())
            assertArrayEquals(approvedBytes, payload.encodeToByteArray())
        }
    }
}
