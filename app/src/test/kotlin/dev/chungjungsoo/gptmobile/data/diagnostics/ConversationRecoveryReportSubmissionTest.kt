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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
