package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportGitHubSubmissionTest {
    @get:Rule val files = TemporaryFolder()
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "@someone <unsafe>", description = "Original approved details\n<!-- forged marker -->")
    private val route = IssueReportRoute(IssueReportRoute.Kind.GITHUB, connectionUid = "verified-connection", accountId = "42", accountLogin = "report-author")
    private val expected get() = IssueReportGitHubSubmission.render(report, report.approvedPayload())

    private fun response(request: Request, code: Int, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun receipt(account: Int = 42, body: kotlinx.serialization.json.JsonElement = expected.getValue("body"), repository: String = IssueReportGitHubSubmission.REPOSITORY) = buildJsonObject {
        put("number", 742)
        put("body", body)
        put("url", "https://api.github.com/repos/$repository/issues/742")
        put("user", buildJsonObject { put("id", account) })
    }

    @Test fun additionalDiagnosticPayloadKeepsExactApprovalThroughNativeCreateAndReadOnlyReconciliation() {
        val context = IssueReportContext.fromFields(
            "VOICE_STATE",
            mapOf("diagnostic_area" to "VOICE", "voice_engine" to "KOKORO", "voice_phase" to "SPEAKING", "voice_first_audio_basis" to "PLAYBACK_HEAD", "voice_response_to_first_audio_ms" to 98),
            1_000L
        )
        val enriched = report.copy(context = context)
        val payload = enriched.approvedPayload()
        val rendered = IssueReportGitHubSubmission.render(enriched, payload)
        val initial = IssueReportOutbox.Entry(report.reportId, payload, storedAtMillis = 1, updatedAtMillis = 1, route = route, deliveryEvidence = IssueReportOutbox.DeliveryEvidence.NOT_DISPATCHED)
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            if (request.method == "POST") {
                assertEquals(rendered.toString(), Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertTrue(rendered.getValue("body").toString().contains("voice_response_to_first_audio_ms"))
                throw IOException("Receipt lost after create")
            }
            response(request, 200, """{"items":[${receipt(body = rendered.getValue("body"))}]}""")
        }.build()
        val submission = IssueReportGitHubSubmission(client)
        var dispatches = 0
        assertTrue(submission.send(initial, "private-token") { dispatches++ } is IssueReportSubmission.Failure)
        val uncertain = initial.copy(deliveryEvidence = IssueReportOutbox.DeliveryEvidence.MAY_HAVE_BEEN_SUBMITTED)
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(uncertain, "private-token") { error("Only a read is permitted") })
        assertEquals(listOf("POST", "GET"), methods)
        assertEquals(1, dispatches)
        assertEquals(payload, uncertain.payload)
    }

    @Test fun explicitApprovalPayloadAndNativeRouteRejectUnknownPrivateDiagnosticFields() {
        val enriched = report.copy(context = IssueReportContext.fromFields("TOOL_RESULT", mapOf("diagnostic_area" to "TOOL", "tool_schema_hash" to "a".repeat(64)), 1_000L))
        val raw = IssueReport.json.parseToJsonElement(enriched.approvedPayload()).jsonObject
        val context = raw.getValue("context").jsonObject
        val capture = context.getValue("diagnostics").jsonObject
        val malformed = JsonObject(raw + ("context" to JsonObject(context + ("diagnostics" to JsonObject(capture + ("rawArguments" to JsonPrimitive("private-arguments"))))))).toString()
        // ignoreUnknownKeys in the historical decoder must not authorize extra public data.
        assertEquals(enriched, IssueReport.json.decodeFromString(IssueReport.serializer(), malformed))
        assertThrows(IllegalArgumentException::class.java) { IssueReportOutbox(files.newFolder()).approve(enriched, malformed) }
        val entry = IssueReportOutbox.Entry(report.reportId, malformed, storedAtMillis = 1, updatedAtMillis = 1, route = route, deliveryEvidence = IssueReportOutbox.DeliveryEvidence.NOT_DISPATCHED)
        val client = OkHttpClient.Builder().addInterceptor { error("Private input must never reach GitHub") }.build()
        val failure = IssueReportGitHubSubmission(client).send(entry, "private-token") { error("Invalid payload cannot dispatch") } as IssueReportSubmission.Failure
        assertEquals("REPORT_PAYLOAD_INVALID", failure.code)
        assertFalse(failure.retryable)
    }

    @Test fun nativeReviewReportsPreserveApprovedVersionTwoAndThreeBytesAcrossRestore() {
        val fresh = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("failed_stage" to "review_comparison", "next_action" to "SELECT_REVIEW_BASELINE"), 1_000L)
        for (version in listOf(2, 3)) {
            val approvedReport = report.copy(context = fresh.copy(schemaVersion = version))
            val payload = approvedReport.approvedPayload()
            val directory = files.newFolder()
            val initial = IssueReportOutbox(directory)
            initial.approve(approvedReport, payload)
            initial.bindRoute(approvedReport.reportId, route)
            val restored = IssueReportOutbox(directory)
            val entry = requireNotNull(restored.load(approvedReport.reportId))
            assertEquals(payload, entry.payload)
            assertEquals(route, entry.route)
            assertThrows(IllegalStateException::class.java) { restored.approve(approvedReport.copy(context = fresh.copy(schemaVersion = if (version == 2) 3 else 2))) }
            val rendered = IssueReportGitHubSubmission.render(approvedReport, payload)
            var posts = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("POST", request.method)
                assertEquals("api.github.com", request.url.host)
                assertEquals(rendered.toString(), Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                posts++
                response(request, 201, receipt(body = rendered.getValue("body")).toString())
            }.build()
            assertEquals(IssueReportSubmission.Confirmed(742), IssueReportGitHubSubmission(client).send(entry, "private-fixture-token") { restored.dispatching(approvedReport.reportId, route) })
            assertEquals(1, posts)
            assertEquals(payload, restored.load(approvedReport.reportId)?.payload)
        }
    }

    @Test fun lostCreateReceiptAndProcessRestartOnlySearchWithoutReplayingTheWrite() = runTest {
        val directory = files.newFolder()
        var outbox = IssueReportOutbox(directory)
        outbox.approve(report)
        outbox.bindRoute(report.reportId, route)
        val methods = mutableListOf<String>()
        var found = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            assertEquals("Bearer private-fixture-token", request.header("Authorization"))
            assertEquals("api.github.com", request.url.host)
            if (request.method == "POST") {
                assertTrue(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
                assertEquals(expected.toString(), Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                throw IOException("Server accepted create; response lost")
            }
            assertEquals("/search/issues", request.url.encodedPath)
            assertTrue(request.url.queryParameter("q")!!.contains(report.reportId))
            response(request, 200, if (found) """{"items":[${receipt()}]}""" else """{"items":[],"incomplete_results":true}""")
        }.build()
        val delivery = IssueReportGitHubSubmission(client)
        suspend fun attempt() = IssueReportDelivery.attempt(outbox, report.reportId, { _, _ ->
            delivery.send(requireNotNull(outbox.load(report.reportId)), "private-fixture-token") { outbox.dispatching(report.reportId, route) }
        })
        assertEquals(IssueReportDelivery.Result.RETRY, attempt())
        outbox = IssueReportOutbox(directory)
        assertEquals(IssueReportDelivery.Result.RETRY, attempt())
        repeat(IssueReportDelivery.MAX_AUTOMATIC_ATTEMPTS - 3) { assertEquals(IssueReportDelivery.Result.RETRY, attempt()) }
        assertEquals(IssueReportDelivery.Result.FAILED, attempt())
        assertEquals(IssueReportDelivery.MAX_AUTOMATIC_ATTEMPTS, outbox.load(report.reportId)?.automaticAttempts)
        // A manual retry still carries the original dispatch evidence and exact bytes.
        outbox.queue(report.reportId, manualRetry = true)
        found = true
        assertEquals(IssueReportDelivery.Result.CONFIRMED, attempt())
        assertEquals(listOf("POST", "GET", "GET", "GET", "GET", "GET"), methods)
        val saved = requireNotNull(outbox.load(report.reportId))
        assertEquals(report.approvedPayload(), saved.payload)
        assertEquals(742, saved.issueNumber)
        assertFalse(IssueReport.json.encodeToString(IssueReportOutbox.Entry.serializer(), saved).contains("private-fixture-token"))
    }

    @Test fun processDeathAfterDurableDispatchButBeforeSocketWriteCannotBecomeAnotherCreate() {
        val directory = files.newFolder()
        val first = IssueReportOutbox(directory)
        first.approve(report)
        first.bindRoute(report.reportId, route)
        first.dispatching(report.reportId, route)
        val restored = requireNotNull(IssueReportOutbox(directory).load(report.reportId))
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            response(chain.request(), 200, """{"items":[]}""")
        }.build()
        assertEquals("REPORT_GITHUB_CONFIRMATION_PENDING", (IssueReportGitHubSubmission(client).send(restored, "token") { error("Reconciliation must not dispatch a create") } as IssueReportSubmission.Failure).code)
        assertEquals(listOf("GET"), methods)
    }

    @Test fun anExplicitGitHubRejectionCanRetryAfterAuthorizationWithoutLosingApprovedIdentity() = runTest {
        val outbox = IssueReportOutbox(files.newFolder())
        outbox.approve(report)
        outbox.bindRoute(report.reportId, route)
        var posts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("POST", chain.request().method)
            posts++
            response(chain.request(), if (posts == 1) 401 else 201, if (posts == 1) "{}" else receipt().toString())
        }.build()
        val delivery = IssueReportGitHubSubmission(client)
        suspend fun attempt() = IssueReportDelivery.attempt(outbox, report.reportId, { _, _ -> delivery.send(requireNotNull(outbox.load(report.reportId)), "token") { outbox.dispatching(report.reportId, route) } })
        assertEquals(IssueReportDelivery.Result.FAILED, attempt())
        assertFalse(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
        outbox.queue(report.reportId, manualRetry = true)
        assertEquals(IssueReportDelivery.Result.CONFIRMED, attempt())
        assertEquals(2, posts)
        assertEquals(report.approvedPayload(), outbox.load(report.reportId)?.payload)
    }

    @Test fun confirmationMustMatchAccountRepositoryExactBodyAndPositiveIssueNumber() {
        val outbox = IssueReportOutbox(files.newFolder())
        outbox.approve(report)
        outbox.bindRoute(report.reportId, route)
        outbox.dispatching(report.reportId, route)
        for (invalid in listOf(receipt(account = 99), receipt(body = JsonPrimitive("Different report")), receipt(repository = "other/project"), JsonObject(receipt() + ("number" to JsonPrimitive(0))), JsonObject(receipt() + ("pull_request" to buildJsonObject {})))) {
            val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 200, """{"items":[$invalid]}""") }.build()
            val result = IssueReportGitHubSubmission(client).send(requireNotNull(outbox.load(report.reportId)), "token") { error("No new create") }
            assertEquals("REPORT_GITHUB_CONFIRMATION_PENDING", (result as IssueReportSubmission.Failure).code)
        }
    }

    @Test fun onlyUndispatchedReportsMayChangeDestinationOrAccount() {
        val outbox = IssueReportOutbox(files.newFolder())
        outbox.approve(report)
        assertFalse(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
        outbox.bindRoute(report.reportId, route)
        outbox.bindRoute(report.reportId, route.copy(accountId = "43", accountLogin = "other-author"))
        assertThrows(IllegalStateException::class.java) { outbox.dispatching(report.reportId, route) }
        outbox.bindRoute(report.reportId, route)
        outbox.dispatching(report.reportId, route)
        assertThrows(IllegalStateException::class.java) { outbox.dispatching(report.reportId, route) }
        assertThrows(IllegalStateException::class.java) { outbox.bindRoute(report.reportId, route.copy(accountId = "43", accountLogin = "other-author")) }
        assertThrows(IllegalStateException::class.java) { outbox.bindRoute(report.reportId, IssueReportRoute(IssueReportRoute.Kind.SERVICE, origin = "https://reports.example.invalid")) }
    }

    @Test fun localMissingConfigurationStaysProvablyUndispatchedAcrossRestart() {
        val directory = files.newFolder()
        val outbox = IssueReportOutbox(directory)
        outbox.approve(report)
        outbox.failed(report.reportId, IssueReportSubmission.Failure("REPORT_SERVICE_UNCONFIGURED", true, automaticRetry = false), automaticRetry = false)
        val restored = IssueReportOutbox(directory)
        assertFalse(requireNotNull(restored.load(report.reportId)).mayHaveBeenSubmitted)
        restored.bindRoute(report.reportId, route)
        val queued = restored.queue(report.reportId, manualRetry = true)
        assertEquals(report.approvedPayload(), queued.payload)
        assertEquals(report.reportId, queued.reportId)
        assertFalse(queued.mayHaveBeenSubmitted)
    }
}
