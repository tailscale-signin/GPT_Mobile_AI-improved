package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportCorrelationIndexTest {
    @Test fun initializationDoesNotResolveStorageOnTheApplicationThread() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(application.cacheDir, "correlation-startup-${UUID.randomUUID()}")
        val accesses = AtomicInteger()
        val owner = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File {
                accesses.incrementAndGet()
                assertFalse(Thread.currentThread() === Looper.getMainLooper().thread)
                return directory
            }
        }
        try {
            IssueReportIncidents.initialize(owner)
            assertEquals(0, accesses.get())
            IssueReportIncidents.retainCorrelation(IssueReport.manual("Report", "Description", emptyList()))
            assertEquals(1, accesses.get())
            assertTrue(File(directory, "issue-report-correlations.json").isFile)
        } finally {
            IssueReportIncidents.initialize(application)
            directory.deleteRecursively()
        }
    }

    @Test fun reportUuidAndSafeIncidentContextSurviveRestartAndRepeatedRetentionWithoutLogs() = runBlocking {
        val file = File.createTempFile("issue-correlation", ".json").apply { delete() }
        try {
            val report = IssueReport.manual("private title", "private description", emptyList()).copy(
                context = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("run_id" to "exact-run", "invocation_id" to "exact-invocation", "http_status" to 409))
            )
            IssueReportCorrelationIndex(file).retain(report)
            val restarted = IssueReportCorrelationIndex(file)
            restarted.retain(report)
            val row = restarted.snapshot().single()
            assertEquals(JsonPrimitive(report.reportId), row["reportId"])
            assertEquals(IssueReport.json.encodeToJsonElement(IssueReportContext.serializer(), report.context!!), row["context"])
            val raw = file.readText()
            assertFalse(raw.contains("private title"))
            assertFalse(raw.contains("private description"))
            assertFalse(raw.contains("exact-run"))
            assertFalse(raw.contains("exact-invocation"))
            assertTrue(runCatching { restarted.retain(report.copy(context = IssueReportContext.fromFields("CODE_ONLY"))) }.isFailure)
            assertEquals(row, restarted.snapshot().single())
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    @Test fun historyIsBoundedAndCorruptionIsExplicitRatherThanAnEmptySuccess() = runBlocking {
        val file = File.createTempFile("issue-correlation", ".json").apply { delete() }
        try {
            val index = IssueReportCorrelationIndex(file)
            val reports = List(65) { IssueReport.debug(ReportFeature.CHAT, "CHAT_FAILED", emptyList()) }
            reports.forEach { index.retain(it) }
            val rows = index.snapshot()
            assertEquals(64, rows.size)
            assertEquals(JsonPrimitive(reports[1].reportId), rows.first()["reportId"])
            assertEquals(JsonPrimitive(reports.last().reportId), rows.last()["reportId"])
            file.writeText("invalid local index")
            assertTrue(runCatching { index.snapshot() }.isFailure)
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }
}
