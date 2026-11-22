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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestByteBudgetReportSubmissionTest {
    private val origin = "https://reports.example.invalid"
    private val feature = "REQUEST_BYTE_BUDGET_V1"
    private val code = "REQUEST_BYTES_EXCEEDED"
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained request", description = "Approved diagnostic — 数据 🛰️")
    private val byteFields = mapOf(
        "request_serialized_bytes" to JsonPrimitive(20_000_000),
        "request_tool_schema_bytes" to JsonPrimitive(8_192),
        "request_evidence_bytes" to JsonPrimitive(18_000_000),
        "provider_request_byte_limit" to JsonPrimitive(16_000_000),
        "provider_request_limit_source" to JsonPrimitive("RESPONSE_HEADER"),
        "payload_replan_allowed" to JsonPrimitive(true)
    )

    @Test fun everyByteDiagnosticRequiresItsOwnCapabilityBeforePostingApprovedBytes() {
        for (fields in byteFields.map { (key, value) -> mapOf(key to value) } + listOf(byteFields)) {
            val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER") + fields, 1_000L)
            val diagnostics = requireNotNull(context.diagnostics)
            assertEquals(fields, diagnostics.values.filterKeys { it in byteFields })
            assertTrue(feature in diagnostics.requiredServiceFeatures())
            assertNegotiatedWithoutRewriting(report.copy(context = context), fields.keys.joinToString())
        }
    }

    @Test fun omittedByteDiagnosticsStillRequireTheirOwnCapability() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "PROVIDER"), 1_000L)
        val diagnostics = requireNotNull(context.diagnostics).copy(omittedFields = byteFields.keys.toList())
        assertNegotiatedWithoutRewriting(report.copy(context = context.withDiagnostics(diagnostics)), "omitted byte diagnostics")
    }

    @Test fun bareByteFailureCodeRequiresItsOwnCapabilityWithoutDiagnosticContext() {
        val approved = report.copy(source = "debug", title = null, description = null, feature = "chat", errorCode = code)
        assertNull(approved.context)
        assertNegotiatedWithoutRewriting(approved, "bare errorCode")
    }

    @Test fun bareByteFailureEventRequiresItsOwnCapabilityWithoutDiagnosticContext() {
        val approved = report.copy(events = listOf("-1ms E chat $code"))
        assertNull(approved.context)
        assertNull(approved.errorCode)
        assertNegotiatedWithoutRewriting(approved, "bare event")
    }

    @Test fun nestedByteFailureCodesRequireTheirOwnCapabilityWithoutByteCounts() {
        for ((location, area) in mapOf("provider_failure_code" to "PROVIDER", "causal_failure_code" to "INCIDENT", "voice_chat_failure_code" to "VOICE")) {
            val context = IssueReportContext.fromFields(if (area == "VOICE") "VOICE_STATE" else "CHAT_FAILURE", mapOf("diagnostic_area" to area, location to code), 1_000L)
            assertEquals(JsonPrimitive(code), context.diagnostics?.values?.get(location))
            assertNegotiatedWithoutRewriting(report.copy(context = context), location)
        }
    }

    private fun assertNegotiatedWithoutRewriting(approved: IssueReport, variant: String) {
        val payload = approved.approvedPayload()
        val approvedBytes = payload.encodeToByteArray()
        // A reporter already supporting the adjacent recovery contract still needs this feature.
        val supportedFeatures = approved.context?.diagnostics?.requiredServiceFeatures().orEmpty() +
            IssueReportIncidentCapsule.CONVERSATION_RECOVERY_FEATURE + feature
        var compatible = false
        var dispatched = 0
        val methods = mutableListOf<String>()
        val posted = mutableListOf<ByteArray>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            val body = when (request.method) {
                "GET" -> {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", 2)
                        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                        put("reportDiagnosticFeatures", JsonArray(supportedFeatures.filter { compatible || it != feature }.map(::JsonPrimitive)))
                    }.toString()
                }
                "POST" -> {
                    assertEquals("$origin/v1/issue-reports", request.url.toString())
                    posted += Buffer().also { requireNotNull(request.body).writeTo(it) }.readByteArray()
                    buildJsonObject {
                        put("reportId", approved.reportId)
                        put("number", 81)
                    }.toString()
                }
                else -> error("Unexpected report request: ${request.method}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val deferred = submission.send(approved.reportId, payload) { dispatched++ }
        assertEquals("$variant must only query readiness on an older reporter", listOf("GET"), methods)
        assertEquals(IssueReportSubmission.Failure("REPORT_SERVICE_UPDATE_REQUIRED", retryable = true, automaticRetry = false), deferred)
        assertFalse((deferred as IssueReportSubmission.Failure).automaticRetry)
        assertEquals(0, dispatched)
        assertTrue(posted.isEmpty())
        assertArrayEquals(approvedBytes, approved.approvedPayload().encodeToByteArray())

        compatible = true
        assertEquals(IssueReportSubmission.Confirmed(81), submission.send(approved.reportId, payload) { dispatched++ })
        assertEquals(listOf("GET", "GET", "POST"), methods)
        assertEquals(1, dispatched)
        assertArrayEquals(approvedBytes, posted.single())
    }
}
