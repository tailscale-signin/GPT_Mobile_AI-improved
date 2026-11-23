package dev.chungjungsoo.gptmobile.data.diagnostics

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
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
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportFrameworkFramesTest {
    private val feature = "FRAMEWORK_EXCEPTION_FRAMES_V1"

    // The Compose method symbol is from ui-android 1.12.1; line numbers here are synthetic.
    private val frameworkFrames = listOf(
        "androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNodeImpl.onPointerEvent-H0pRuoY:100",
        "androidx.collection.IntList.first:200",
        "kotlinx.coroutines.flow.FlowKt__ReduceKt.first:300"
    )

    private fun frame(className: String, method: String = "read", line: Int = 1) =
        StackTraceElement(className, method, "/private/conversation/source.kt", line)

    private fun report(frames: List<String>) = IssueReport(
        "75d84431-c695-4ff4-973b-3a80d63d5993", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture",
        feature = "app", errorCode = "JVM_CRASH", errorType = "NoSuchElementException", frames = frames
    )

    private fun response(request: Request, code: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun frameworkThrowSitesSurviveApprovedSerializationWithoutMessagesOrPaths() {
        val failure = NoSuchElementException("private conversation and credential value").apply {
            stackTrace = arrayOf(
                frame("androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNodeImpl", "onPointerEvent-H0pRuoY", 100),
                frame("androidx.collection.IntList", "first", 200),
                frame("kotlinx.coroutines.flow.FlowKt__ReduceKt", "first", 300)
            )
        }
        val (type, frames) = IssueReport.exceptionMetadata(failure)
        assertEquals("NoSuchElementException", type)
        assertEquals(frameworkFrames, frames)
        val approved = report(frames).approvedPayload()
        val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), approved)
        assertEquals(frames, restored.frames)
        assertEquals(approved, restored.approvedPayload())
        assertFalse(approved.contains("private conversation"))
        assertFalse(approved.contains("/private/"))
        assertTrue(approved.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
    }

    @Test fun neighboringNamespacesAndMalformedOrOverlongSymbolsStayExcluded() {
        val disallowed = listOf(
            frame("androidxevil.collection.Reader"),
            frame("kotlinx.coroutinesExtra.Reader"),
            frame("kotlinx.serialization.Reader"),
            frame("example.customer.PrivateReader"),
            frame("androidx.compose.Reader", "private value"),
            frame("androidx.compose.Reader", "read\nprivate"),
            frame("androidx.compose.Reader", "read/secret"),
            frame("androidx.compose.Reader", "read-../secret"),
            frame("androidx.compose.Reader", "read-\nprivate"),
            frame("androidx.compose.Reader-secret", "read"),
            frame("androidx.compose.Reader", "read", 1_000_000),
            frame("androidx." + "a".repeat(144), "m")
        )
        val allowed = frame("androidx." + "a".repeat(143), "m", 999_999)
        val failure = NoSuchElementException().apply { stackTrace = (disallowed + allowed).toTypedArray() }
        assertEquals(listOf("${allowed.className}.m:999999"), IssueReport.exceptionMetadata(failure).second)
    }

    @Test fun existingFrameAndCauseLimitsStillReserveApplicationEvidence() {
        val frames = (1..30).map { frame("androidx.compose.Runtime", "step$it", it) } +
            frame("dev.chungjungsoo.gptmobile.presentation.chat.ChatViewModel", "handleAction", 31)
        val failure = NoSuchElementException().apply { stackTrace = frames.toTypedArray() }
        val retained = IssueReport.exceptionMetadata(failure).second
        assertEquals(8, retained.size)
        assertEquals(frames.take(4).map { "${it.className}.${it.methodName}:${it.lineNumber}" }, retained.take(4))
        assertTrue(retained.any { it.contains("ChatViewModel.handleAction:31") })

        var chain: Throwable? = null
        for (index in 5 downTo 1) {
            chain = IllegalStateException("private message", chain).apply {
                stackTrace = arrayOf(frame("kotlinx.coroutines.Runtime", "cause$index", index))
            }
        }
        assertEquals((1..4).map { "kotlinx.coroutines.Runtime.cause$it:$it" }, IssueReport.exceptionMetadata(chain).second)
        val outsideInspection = NoSuchElementException().apply {
            stackTrace = (List(48) { frame("example.private.Reader") } + frame("androidx.collection.IntList", "first", 49)).toTypedArray()
        }
        assertTrue(IssueReport.exceptionMetadata(outsideInspection).second.isEmpty())
    }

    @Test fun capturedExceptionBridgeKeepsItsApplicationOnlyProvenanceBoundary() {
        val application = "dev.chungjungsoo.gptmobile.presentation.chat.ChatViewModel.handleAction:31"
        val failure = NoSuchElementException("private conversation").apply {
            stackTrace = arrayOf(
                frame("androidx.collection.IntList", "first", 200),
                frame("kotlinx.coroutines.flow.FlowKt__ReduceKt", "first", 300),
                frame("dev.chungjungsoo.gptmobile.presentation.chat.ChatViewModel", "handleAction", 31)
            )
        }
        val fields = IssueReportIncidentCapsule.exceptionFields(failure, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH)
        assertEquals(JsonArray(listOf(JsonPrimitive(application))), fields["exception_application_frames"])
        assertEquals(listOf(application), report(emptyList()).withCapturedException(fields).frames)
        val claimed = fields + ("exception_application_frames" to JsonArray((frameworkFrames + application).map(::JsonPrimitive)))
        assertEquals(listOf(application), report(emptyList()).withCapturedException(claimed).frames)
        assertTrue(report(emptyList()).withCapturedException(claimed - "incident_phase_provenance").frames.isEmpty())
        assertFalse(fields.toString().contains("private conversation"))
    }

    @Test fun hostedDeliveryWaitsForFrameworkSupportWithoutRewritingApprovedBytes() {
        val approved = report(frameworkFrames)
        val payload = approved.approvedPayload()
        var supported = false
        var version: JsonPrimitive = JsonPrimitive(2)
        val methods = mutableListOf<String>()
        val posted = mutableListOf<String>()
        var dispatches = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            if (request.method == "GET") {
                assertEquals("https://reports.example.invalid/health/ready?capabilities=extended", request.url.toString())
                response(
                    request,
                    200,
                    buildJsonObject {
                        put("service", "gpt-mobile-issue-reporter")
                        put("version", version)
                        put("reportContextVersions", JsonArray(emptyList()))
                        put("reportDiagnosticFeatures", JsonArray(if (supported) listOf(JsonPrimitive(feature)) else emptyList()))
                    }.toString()
                )
            } else {
                posted += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                response(request, 201, """{"reportId":"${approved.reportId}","number":742}""")
            }
        }.build()
        val submission = IssueReportSubmission("https://reports.example.invalid", client)
        for (capability in listOf(false to JsonPrimitive(2), true to JsonPrimitive(1), true to JsonPrimitive("2"))) {
            supported = capability.first
            version = capability.second
            val outcome = submission.send(approved.reportId, payload) { dispatches++ }
            assertTrue(outcome is IssueReportSubmission.Failure)
            val blocked = outcome as IssueReportSubmission.Failure
            assertEquals("REPORT_SERVICE_UPDATE_REQUIRED", blocked.code)
            assertTrue(blocked.retryable)
            assertFalse(blocked.automaticRetry)
            assertEquals(0, dispatches)
            assertTrue(posted.isEmpty())
        }
        supported = true
        version = JsonPrimitive(2)
        assertEquals(IssueReportSubmission.Confirmed(742), submission.send(approved.reportId, payload) { dispatches++ })
        assertEquals(1, dispatches)
        assertEquals(listOf("GET", "GET", "GET", "GET", "POST"), methods)
        assertEquals(listOf(payload), posted)
        assertEquals(payload, approved.approvedPayload())
    }

    @Test fun mangledApplicationFramesAlsoWaitForTheExtendedGrammar() {
        val sample = report(listOf("dev.chungjungsoo.gptmobile.presentation.chat.ChatScreenKt.Render-H0pRuoY:1"))
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods += chain.request().method
            assertEquals("GET", chain.request().method)
            assertEquals("extended", chain.request().url.queryParameter("capabilities"))
            response(chain.request(), 200, """{"service":"gpt-mobile-issue-reporter","version":2,"reportContextVersions":[],"reportDiagnosticFeatures":[]}""")
        }.build()
        assertEquals(
            IssueReportSubmission.Failure("REPORT_SERVICE_UPDATE_REQUIRED", retryable = true, automaticRetry = false),
            IssueReportSubmission("https://reports.example.invalid", client).send(sample.reportId, sample.approvedPayload())
        )
        assertEquals(listOf("GET"), methods)
    }

    @Test fun legacyFramesAndUserTextDoNotInventFrameworkNegotiation() {
        val legacy = report(listOf("android.view.View.dispatchTouchEvent:1", "kotlin.coroutines.Continuation.resumeWith:2"))
        val manual = IssueReport(legacy.reportId, "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Report", description = frameworkFrames.joinToString("\n"))
        for (sample in listOf(legacy, manual)) {
            val methods = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                methods += chain.request().method
                response(chain.request(), 201, """{"reportId":"${sample.reportId}","number":742}""")
            }.build()
            assertEquals(IssueReportSubmission.Confirmed(742), IssueReportSubmission("https://reports.example.invalid", client).send(sample.reportId, sample.approvedPayload()))
            assertEquals(listOf("POST"), methods)
        }
    }

    @Test fun nativeGitHubRenderingKeepsTheExactApprovedFrameworkPayload() {
        val approved = report(frameworkFrames)
        val payload = approved.approvedPayload()
        val rendered = IssueReportGitHubSubmission.render(approved, payload)
        val body = (rendered.getValue("body") as JsonPrimitive).content
        assertTrue(body.contains("    $payload"))
        assertTrue(body.contains("<!-- gpt-mobile-report:${approved.reportId} -->"))
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(body.contains("<!-- approved-payload-sha256:$hash -->"))
        val restored = IssueReport.json.parseToJsonElement(payload).jsonObject
        assertEquals(JsonArray(frameworkFrames.map(::JsonPrimitive)), restored["frames"])
        assertEquals(payload, approved.approvedPayload())
    }
}
