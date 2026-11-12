package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
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

class IssueReportSubmissionTest {
    private val origin = "https://reports.example.invalid"
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained report", description = "The observed failure")
    private fun response(request: Request, code: Int, body: String, retryAfter: String? = null) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .apply { retryAfter?.let { header("Retry-After", it) } }.body(body.toResponseBody("application/json".toMediaType())).build()
    private fun body(request: Request) = Buffer().also { request.body?.writeTo(it) }.readUtf8()

    @Test fun lostReceiptRetriesTheExactApprovedPayloadAndAcceptsBoundConfirmation() {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += body(chain.request())
            assertEquals("$origin/v1/issue-reports", chain.request().url.toString())
            if (requests.size == 1) throw IOException("Receipt lost after acceptance")
            response(chain.request(), 200, """{"reportId":"${report.reportId}","number":742}""")
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val approved = report.approvedPayload()
        assertTrue(submission.send(report.reportId, approved) is IssueReportSubmission.Failure)
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, approved))
        assertEquals(listOf(approved, approved), requests)
    }

    @Test fun legacyReceiptsRemainValidButExplicitWrongIdentityNeverConfirmsSuccess() {
        for ((receipt, confirmed) in listOf("""{"number":742}""" to true, """{"reportId":"other-report","number":742}""" to false, """{"reportId":null,"number":742}""" to false, """{"reportId":42,"number":742}""" to false, """{"number":"742"}""" to false, """{"number":0}""" to false, "not JSON" to false)) {
            val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 200, receipt) }.build()
            val result = IssueReportSubmission(origin, client).send(report.reportId, report.approvedPayload())
            assertEquals(receipt, confirmed, result is IssueReportSubmission.Confirmed)
            if (!confirmed) assertTrue((result as IssueReportSubmission.Failure).retryable)
        }
    }

    @Test fun newContextWaitsForDeclaredServiceSupportWithoutDowngradingApprovedBytes() {
        val enriched = report.copy(context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = 1_000L))
        val payload = enriched.approvedPayload()
        val methods = mutableListOf<String>()
        val posted = mutableListOf<String>()
        var readiness = """{"service":"gpt-mobile-issue-reporter","version":1}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            if (request.method == "GET") {
                assertEquals("$origin/health/ready", request.url.toString())
                response(request, 200, readiness)
            } else {
                posted += body(request)
                response(request, 200, """{"reportId":"${report.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission(origin, client)
        val stopped = submission.send(report.reportId, payload) as IssueReportSubmission.Failure
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", stopped.code)
        assertTrue(stopped.retryable)
        assertFalse(stopped.automaticRetry)
        assertEquals(listOf("GET"), methods)
        readiness = """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":["1"]}"""
        assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", (submission.send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        assertTrue(posted.isEmpty())
        readiness = """{"service":"gpt-mobile-issue-reporter","version":1,"reportContextVersions":[1]}"""
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(report.reportId, payload))
        assertEquals(listOf("GET", "GET", "GET", "POST"), methods)
        assertEquals(listOf(payload), posted)
    }

    @Test fun transientAndPermanentRejectionsHaveBoundedSafeMetadataAndRetryDeadlines() {
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 429, """{"error":"RATE_LIMITED","private":"must not be surfaced"}""", "120") }.build()
        val failure = IssueReportSubmission(origin, client) { 10_000L }.send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure
        assertEquals("RATE_LIMITED", failure.code)
        assertEquals(429, failure.httpStatus)
        assertEquals(130_000L, failure.retryNotBeforeMillis)
        assertTrue(failure.retryable)
        val rejected = OkHttpClient.Builder().addInterceptor { response(it.request(), 422, """{"error":"INVALID_REPORT"}""") }.build()
        val permanent = IssueReportSubmission(origin, rejected).send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure
        assertFalse(permanent.retryable)
        assertEquals("INVALID_REPORT", permanent.code)
        val unsafe = OkHttpClient.Builder().addInterceptor { response(it.request(), 403, """{"error":"private arbitrary response"}""") }.build()
        assertEquals("REPORT_HTTP_403", (IssueReportSubmission(origin, unsafe).send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure).code)
    }

    @Test fun unconfiguredUnsafeOriginAndCorruptPayloadNeverDispatch() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            calls++
            response(it.request(), 200, """{"number":742}""")
        }.build()
        for (invalid in listOf("", "http://reports.example.invalid", "$origin/path", "https://user:secret@reports.example.invalid", "$origin?other=1")) {
            val result = IssueReportSubmission(invalid, client).send(report.reportId, report.approvedPayload()) as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UNCONFIGURED", result.code)
            assertFalse(result.automaticRetry)
        }
        for (payload in listOf("not JSON", "{}", report.approvedPayload().replace(report.reportId, "2e0ef31f-f454-4785-8d93-16d538fd1168"))) {
            assertEquals("REPORT_PAYLOAD_INVALID", (IssueReportSubmission(origin, client).send(report.reportId, payload) as IssueReportSubmission.Failure).code)
        }
        assertEquals(0, calls)
    }
}
