package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFinalRequestSubmissionTest {
    private val origin = "https://reports.example.invalid"
    private val base = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained report", description = "Fictional failure")

    @Test fun everySupportedNewCodeOrOutcomeSurfaceRequiresItsOwnCapabilityBeforePostingApprovedBytes() {
        val code = PrivacyFinalRequestFailureEvidence.CODE
        val feature = PrivacyFinalRequestFailureEvidence.FEATURE
        val observed = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf(
                "diagnostic_area" to "PROVIDER",
                "provider_failure_code" to code,
                "provider_failure_stage" to "ADMISSION",
                "provider_failure_domain" to "APPLICATION_VERIFICATION",
                PrivacyFinalRequestFailureEvidence.FIELD to "WIRE_VALIDATION_UNAVAILABLE"
            ),
            1_000L
        )
        val capture = requireNotNull(observed.diagnostics)
        val original = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf("failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "original_interruption" to mapOf("code" to code)),
            1_000L
        )
        assertEquals(code, original.originalFailureCode)
        val variants = mutableListOf(
            base.copy(source = "debug", feature = "chat", errorCode = code, title = null, description = null),
            base.copy(events = listOf("-1ms E chat $code")),
            base.copy(context = original),
            base.copy(context = observed),
            base.copy(context = observed.withDiagnostics(capture.copy(values = emptyMap(), omittedFields = listOf(PrivacyFinalRequestFailureEvidence.FIELD))))
        )
        for ((field, area) in listOf("provider_failure_code" to "PROVIDER", "causal_failure_code" to "INCIDENT", "voice_chat_failure_code" to "VOICE")) {
            val context = IssueReportContext.fromFields(if (area == "VOICE") "VOICE_STATE" else "CHAT_FAILURE", mapOf("diagnostic_area" to area, field to code), 1_000L)
            assertEquals(JsonPrimitive(code), context.diagnostics?.values?.get(field))
            variants += base.copy(context = context)
        }
        for (approved in variants) {
            val payload = approved.approvedPayload()
            val required = approved.context?.diagnostics?.requiredServiceFeatures().orEmpty() + feature
            var supported = false
            var dispatches = 0
            val methods = mutableListOf<String>()
            val posted = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "GET") {
                    assertEquals("$origin/health/ready?capabilities=extended", request.url.toString())
                    val features = (required.filter { supported || it != feature } + PrivacyFailureEvidence.FEATURE).distinct()
                    response(request, readiness(features))
                } else {
                    posted += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    response(request, """{"reportId":"${approved.reportId}","number":2741}""")
                }
            }.build()
            val sender = IssueReportSubmission(origin, client)
            val blocked = sender.send(approved.reportId, payload) { dispatches++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertFalse(blocked.automaticRetry)
            assertEquals(listOf("GET"), methods)
            assertEquals(0, dispatches)
            assertTrue(posted.isEmpty())
            supported = true
            assertEquals(IssueReportSubmission.Confirmed(2741), sender.send(approved.reportId, payload) { dispatches++ })
            assertEquals(listOf("GET", "GET", "POST"), methods)
            assertEquals(1, dispatches)
            assertEquals(listOf(payload), posted)
            assertEquals(payload, approved.approvedPayload())
        }
    }

    @Test fun existingPrivacyEligibilityReportsStillPostWithoutTheNewFinalRequestCapability() {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf("diagnostic_area" to "PROVIDER", "provider_failure_code" to PrivacyFailureEvidence.CODE, "privacy_reason_code" to "VERIFICATION_UNAVAILABLE", "privacy_status" to "NOT_VERIFIED"),
            1_000L
        )
        val required = requireNotNull(context.diagnostics).requiredServiceFeatures()
        assertTrue(PrivacyFailureEvidence.FEATURE in required)
        assertFalse(PrivacyFinalRequestFailureEvidence.FEATURE in required)
        val approved = base.copy(context = context)
        val payload = approved.approvedPayload()
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            if (request.method == "GET") {
                response(request, readiness(required))
            } else {
                assertEquals(payload, Buffer().also { request.body?.writeTo(it) }.readUtf8())
                response(request, """{"reportId":"${approved.reportId}","number":2742}""")
            }
        }.build()
        assertEquals(IssueReportSubmission.Confirmed(2742), IssueReportSubmission(origin, client).send(approved.reportId, payload))
        assertEquals(listOf("GET", "POST"), methods)
    }

    private fun readiness(features: Collection<String>): String = buildJsonObject {
        put("service", "gpt-mobile-issue-reporter")
        put("version", 2)
        put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
        put("reportDiagnosticFeatures", JsonArray(features.map(::JsonPrimitive)))
    }.toString()

    private fun response(request: Request, body: String): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
}
