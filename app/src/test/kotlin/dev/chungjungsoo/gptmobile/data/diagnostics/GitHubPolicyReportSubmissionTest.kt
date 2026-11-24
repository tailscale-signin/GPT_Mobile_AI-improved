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
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPolicyReportSubmissionTest {
    private val origin = "https://reports.example.invalid"
    private val feature = IssueReportDiagnostics.POLICY_SELECTION_FEATURE
    private val newNames = setOf("githubProfileOverride.nextActionPolicy", "githubProfileOverride.evidenceElaboration", "githubProfileOverride.primaryPatchReviewConsent")
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture", feature = "chat", errorCode = "CHAT_FAILED")

    @Test fun newPolicyNamesRequireMatchingReporterBeforeApprovedDispatch() {
        for (name in newNames) {
            val approved = reportFor(listOf(name))
            val snapshot = requireNotNull(approved.context?.diagnostics)
            assertEquals(JsonArray(listOf(JsonPrimitive(name))), snapshot.values["changed_policy_fields"])
            assertTrue("ISSUE_RECOVERY_DETAILS_V1" in snapshot.requiredServiceFeatures())
            assertTrue(feature in snapshot.requiredServiceFeatures())
            assertFalse(IssueReportDiagnostics.MISSING_TOOL_IDENTITY_FEATURE in snapshot.requiredServiceFeatures())
            val restored = requireNotNull(IssueReportDiagnostics.fromJson(IssueReport.json.parseToJsonElement(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot))))
            assertEquals(snapshot, restored)
            assertEquals(snapshot.requiredServiceFeatures(), restored.requiredServiceFeatures())
            assertNegotiation(approved.copy(context = requireNotNull(approved.context).withDiagnostics(restored)), needsUpdate = true)
        }
    }

    @Test fun clippedPolicyCardinalityNegotiatesWhenNewNamesAreNotInTheSample() {
        for (count in 82..84) {
            val approved = reportFor(IssueReportIncidentCapsule.policyFieldNames.sorted().take(count))
            val snapshot = requireNotNull(approved.context?.diagnostics)
            val retained = requireNotNull(snapshot.values["changed_policy_fields"] as? JsonArray)
            assertEquals(8, retained.size)
            assertTrue(retained.none { it in newNames.map(::JsonPrimitive) })
            assertEquals(JsonPrimitive(count - 8), snapshot.values["changed_policy_omitted_count"])
            assertEquals(JsonPrimitive("PARTIAL"), snapshot.values["changed_policy_observation"])
            assertTrue(feature in snapshot.requiredServiceFeatures())
            assertNegotiation(approved, needsUpdate = true)
        }
    }

    @Test fun legacyPolicyEvidenceStillUsesExistingReporterCapabilities() {
        val legacyNames = IssueReportIncidentCapsule.policyFieldNames - newNames
        assertEquals(81, legacyNames.size)
        for (names in listOf(listOf("githubProfileOverride.sampling"), legacyNames.sorted())) {
            val approved = reportFor(names)
            val snapshot = requireNotNull(approved.context?.diagnostics)
            assertFalse(feature in snapshot.requiredServiceFeatures())
            assertNegotiation(approved, needsUpdate = false)
        }
    }

    private fun reportFor(names: List<String>): IssueReport {
        val fields = IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "DISPATCH_POLICY_CHANGED", mapOf("changed_policy_fields" to names))
        val context = IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_000L)
        assertTrue(requireNotNull(context.diagnostics).isValid())
        return report.copy(context = context)
    }

    private fun assertNegotiation(approved: IssueReport, needsUpdate: Boolean) {
        val payload = approved.approvedPayload()
        val required = requireNotNull(approved.context?.diagnostics).requiredServiceFeatures()
        assertEquals(needsUpdate, feature in required)
        assertTrue("CHANGED_POLICY_FIELDS_V1" in required)
        var compatible = false
        var dispatched = 0
        val posted = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.method
            val body = if (request.method == "GET") {
                assertEquals("$origin/health/ready" + if (needsUpdate) "?capabilities=extended" else "", request.url.toString())
                buildJsonObject {
                    put("service", "gpt-mobile-issue-reporter")
                    put("version", 2)
                    put("reportContextVersions", JsonArray((1..7).map(::JsonPrimitive)))
                    put("reportDiagnosticFeatures", JsonArray((if (compatible) required else required - feature).map(::JsonPrimitive)))
                }.toString()
            } else {
                assertEquals("POST", request.method)
                assertEquals("$origin/v1/issue-reports", request.url.toString())
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
        if (needsUpdate) {
            val deferred = submission.send(approved.reportId, payload) { dispatched++ } as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", deferred.code)
            assertFalse(deferred.automaticRetry)
            assertEquals(0, dispatched)
            assertEquals(emptyList<String>(), posted)
            assertEquals(listOf("GET"), requests)
            compatible = true
        }
        assertEquals(IssueReportSubmission.Confirmed(81), submission.send(approved.reportId, payload) { dispatched++ })
        assertEquals(1, dispatched)
        assertEquals(listOf(payload), posted)
        assertEquals(if (needsUpdate) listOf("GET", "GET", "POST") else listOf("GET", "POST"), requests)
        assertEquals(payload, approved.approvedPayload())
    }
}
