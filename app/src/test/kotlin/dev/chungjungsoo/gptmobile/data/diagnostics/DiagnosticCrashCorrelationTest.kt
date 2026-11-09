package dev.chungjungsoo.gptmobile.data.diagnostics

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticCrashCorrelationTest {
    @Test fun composeCheckpointAndAndroidFatalRepresentTwoCrashesAcrossAProcessRestart() {
        val lines = episode("first", 42, "process-a", 0) + episode("second", 43, "process-b", 30_000)
        val rows = export(lines)
        assertEquals(2, rows.count { it["countAsIssue"] == JsonPrimitive(true) })
        assertEquals(2, rows.mapNotNull { it["incidentId"] }.distinct().size)
        rows.filter { it["message"].toString().contains("Process:") }.forEach {
            assertFalse(it["incidentRoot"] == JsonPrimitive(true))
            assertFalse(it["countAsIssue"] == JsonPrimitive(true))
        }
        val summary = DiagnosticExportSummary()
        rows.forEach { summary.accept(it.toString()) }
        assertEquals(JsonPrimitive(2), Json.parseToJsonElement(summary.line(0)).jsonObject["correlatedIncidents"])
    }

    @Test fun samePidAndTimestampCannotJoinDifferentProcessSessionsOrStacks() {
        listOf(
            episode("other-session", 42, "process-b", 50, sources = setOf("AndroidRuntime")),
            episode("other-stack", 42, "process-a", 50, frameMethod = "differentThrowSite", sources = setOf("AndroidRuntime")),
            episode("other-thread", 42, "process-a", 50, thread = "worker", sources = setOf("AndroidRuntime")),
            episode("later", 42, "process-a", 2_000, sources = setOf("AndroidRuntime"))
        ).forEach { other ->
            val first = episode("first", 42, "process-a", 0, sources = setOf("AppCrash"))
            assertEquals(2, export(first + other).count { it["countAsIssue"] == JsonPrimitive(true) })
        }
    }

    @Test fun repeatedExceptionsFromOneEmitterRemainDistinctAndAmbiguousMatchesStayUnmerged() {
        val first = episode("first", 42, "process-a", 0, sources = setOf("AppCrash"))
        val second = episode("second", 42, "process-a", 50, sources = setOf("AppCrash"))
        val android = episode("android", 42, "process-a", 100, sources = setOf("AndroidRuntime"))
        assertEquals(3, export(first + second + android).count { it["countAsIssue"] == JsonPrimitive(true) })
    }

    @Test fun legacyRecordsCanUseTheirMatchingStackWithoutInventingAProcessSession() {
        val legacy = episode("legacy", 42, "process-a", 0).map { it.replace("processSessionId=process-a · ", "").replace("processName=app · ", "") }
        assertEquals(1, export(legacy).count { it["countAsIssue"] == JsonPrimitive(true) })
    }

    @Test fun rotatedPartialCopyCannotDuplicateTheFirstStackFrameDuringIndexing() {
        val checkpoint = episode("checkpoint", 42, "process-a", 0, sources = setOf("AppCrash"))
        val android = episode("android", 42, "process-a", 50, sources = setOf("AndroidRuntime"))
        assertEquals(1, export(checkpoint.take(2) + android + checkpoint).count { it["countAsIssue"] == JsonPrimitive(true) })
    }

    @Test fun historicalOsExitRemainsIndependentWithoutAnExplicitCrashReceiptLink() {
        val lines = episode("crash", 42, "process-a", 0) +
            "2026-11-09T03:37:42Z I/ProcessExit: eventId=os-exit · processId=42 · reasonCode=4"
        val rows = export(lines)
        assertEquals(2, rows.count { it["countAsIssue"] == JsonPrimitive(true) })
        assertEquals(JsonPrimitive("os-exit"), rows.last()["incidentId"])
    }

    @Test fun processHeaderIsContextEvenWhenTheRetainedCaptureStartsMidCrash() {
        val rows = export(listOf("2026-11-09T03:37:41Z E/Android: eventId=process-only · E/AndroidRuntime(42): Process: app, PID: 42"))
        assertEquals(JsonPrimitive(false), rows.single()["countAsIssue"])
        assertEquals(JsonPrimitive("crash_context"), rows.single()["category"])
    }

    private fun export(lines: List<String>): List<kotlinx.serialization.json.JsonObject> {
        val tracker = DiagnosticIncidentTracker()
        fun visit(consume: (String, DiagnosticCaptureCursor.Record) -> Unit) {
            val cursor = DiagnosticCaptureCursor("current.log") { true }
            lines.forEachIndexed { index, line -> consume(line, cursor.accept(line, index)) }
        }
        visit { line, record -> tracker.index(line, record.identity, record.parent) }
        tracker.finishIndex()
        return buildList {
            visit { line, record ->
                val incident = tracker.accept(line, record.identity, record.parent)
                add(Json.parseToJsonElement(structuredDiagnosticLine(line, captureIdentity = record.identity, parentEventId = record.parent, incidentId = incident.id, incidentRoot = incident.first)).jsonObject)
            }
        }
    }

    private fun episode(
        name: String,
        pid: Int,
        session: String,
        offset: Long,
        frameMethod: String = "diagnosticFrameTiming",
        thread: String = "main",
        sources: Set<String> = setOf("ComposeInternal", "AppCrash", "AndroidRuntime")
    ): List<String> = buildList {
        var sequence = 0
        fun append(source: String, body: String): String {
            val id = "$name-${sequence++}"
            val time = Instant.parse("2026-11-09T03:37:41Z").plusMillis(offset + sequence)
            val tag = if (source == "AppCrash") "F/AppCrash" else "E/Android"
            val android = if (source == "AppCrash") "" else "E/$source($pid): "
            add("$time $tag: eventId=$id · processId=$pid · processName=app · processSessionId=$session · $android$body")
            return id
        }
        val frames = listOf("dev.chungjungsoo.gptmobile.DiagnosticFrameTimingKt.$frameMethod", "dev.chungjungsoo.gptmobile.GatewayScreen.render")
        if ("ComposeInternal" in sources) {
            append("ComposeInternal", "Error was captured in composition.")
            append("ComposeInternal", "java.lang.IllegalArgumentException: Failed requirement.")
            frames.forEach { append("ComposeInternal", "at $it(Screen.kt:13)") }
        }
        if ("AppCrash" in sources) {
            val parent = append("AppCrash", "type=java.lang.IllegalArgumentException · thread=$thread · topFrameOrigin=application")
            frames.forEachIndexed { index, frame -> add("parentEventId=$parent · sequence=${index + 1} · at $frame(line=13; native=false)") }
        }
        if ("AndroidRuntime" in sources) {
            append("AndroidRuntime", "FATAL EXCEPTION: $thread")
            append("AndroidRuntime", "Process: app, PID: $pid")
            append("AndroidRuntime", "java.lang.IllegalArgumentException: Failed requirement.")
            frames.forEach { append("AndroidRuntime", "at $it(Screen.kt:13)") }
        }
    }
}
