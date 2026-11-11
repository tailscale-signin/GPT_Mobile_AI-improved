package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AndroidDiagnosticExportMemory
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureResult
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureStorage
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticDatabaseSource
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticExportMemory
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticLogSnapshot
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticLogSource
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticValueProjector
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.RuntimeIncidentCapture
import dev.chungjungsoo.gptmobile.data.diagnostics.StagedDiagnosticCapture
import dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StagedChatDebugExportTest {
    private lateinit var context: Context
    private lateinit var destination: File
    private lateinit var database: DiagnosticDatabaseSource
    private val chatId = (UUID.randomUUID().hashCode() ushr 1).coerceAtLeast(1)
    private var captureCount = 0
    private var retainRecentLogs = true
    private var readSection: suspend (String, suspend () -> Unit, suspend (JsonObject) -> Unit) -> Unit = { _, _, _ -> }

    @Test fun configurationSnapshotExplainsReasoningAndGatewaySelectionWithoutClaimingExecution() = runBlocking {
        val profile = PlatformV2(uid = "selected-profile", name = "Profile", compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA, reasoning = false, token = "private-secret")
        val features = AppFeatureSettings(githubProfileOverrides = mapOf(profile.uid to dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride(engineeringGatewayProfileUid = "selected-gateway")))
        val result = writeStagedChatDiagnostics(context, destination, ChatRoomV2(chatId, "Test"), listOf(profile), ChatMcpToolConfig(), features, "", emptyList(), database, null, false, false) {}
        val settings = archive(result).getValue("feature-settings.jsonl")
        assertTrue(settings.contains("CURRENT_SNAPSHOT_NOT_EXECUTION_PROOF"))
        assertTrue(settings.contains("selected-gateway"))
        assertTrue(settings.contains("resolvedPreference"))
        assertTrue(settings.contains("OFF"))
        assertTrue(settings.contains("NOT_MEASURED_BY_SETTINGS"))
        assertFalse(settings.contains("private-secret"))
    }

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        destination = File(context.cacheDir, "export-test-${UUID.randomUUID()}")
        database = mockk()
        mockkObject(AppLogRecorder, AndroidDiagnosticExportMemory, LocalRuntimeIncidentHistory, FreeProviderDiagnostics, RunDiagnostics)
        every { AndroidDiagnosticExportMemory.sample() } returns DiagnosticExportMemory(1, 256L * 1024 * 1024, 64L * 1024 * 1024, 32L * 1024 * 1024, 224L * 1024 * 1024)
        every { LocalRuntimeIncidentHistory.capture() } returns RuntimeIncidentCapture("AVAILABLE", 123, emptyList(), 0)
        every { FreeProviderDiagnostics.snapshot(any()) } returns emptyList()
        every { RunDiagnostics.journal } returns null
        every { AppLogRecorder.captureForExport(any(), any()) } answers {
            captureCount++
            val directory = firstArg<File>().apply { mkdirs() }
            val sources = listOf("critical.log" to "tool-failure private-secret\n", "current.log" to "lifecycle private-secret\n").filter { retainRecentLogs || it.first.startsWith("critical") }.map { (name, text) ->
                val file = File(directory, name).apply { writeText(text) }
                DiagnosticLogSource(name, file, 1, file.length())
            }
            DiagnosticLogSnapshot(sources, 123, true, 0, snapshotDirectory = directory)
        }
        coEvery { database.capture(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val consume = arg<suspend (JsonObject) -> Unit>(4)
            val omitted = arg<(String) -> Unit>(5)
            // The real database producer projects each bounded row before passing it to the adapter.
            val maximumSourceBytes = arg<Int>(6)
            readSection(arg(1), arg(3)) { consume(DiagnosticValueProjector(maximumSourceBytes, omitted).element(it) as JsonObject) }
        }
    }

    @After fun clean() {
        DiagnosticCaptureStorage.deleteChat(context, chatId)
        destination.deleteRecursively()
        unmockkAll()
    }

    @Test fun failedConversationStillExportsLogsProfilesAndToolReceiptsWithoutCredentials() = runBlocking {
        readSection = { section, before, consume ->
            before()
            if (section == "conversation") throw IOException("sensitive-error-message")
            if (section == "tool_receipts") {
                consume(
                    buildJsonObject {
                        put("tool", "github_read")
                        put("error", "GITHUB_TRANSIENT_NETWORK")
                        put("payload", "private-secret")
                    }
                )
            }
        }
        val result = capture()
        val files = archive(result)
        assertTrue(files.getValue("critical-app-logs.jsonl").contains("tool-failure"))
        assertTrue(files.getValue("tool-receipts.jsonl").contains("GITHUB_TRANSIENT_NETWORK"))
        assertTrue(files.getValue("profiles.jsonl").contains("Profile"))
        assertEquals(JsonPrimitive("FAILED"), stage(result, "conversation")["status"])
        assertFalse(files.values.any { "private-secret" in it || "sensitive-error-message" in it })
        assertTrue(result.retryRecommended)
    }

    @Test fun cancelledCaptureResumesWithoutRecapturingRotatedLogs() = runBlocking {
        readSection = { section, before, _ ->
            before()
            if (section == "conversation") throw CancellationException("interrupted")
        }
        assertTrue(runCatching { capture() }.exceptionOrNull() is CancellationException)
        assertNotNull(StagedDiagnosticCapture.findResumableSession(DiagnosticCaptureStorage.directory(context, chatId), diagnosticCaptureIdentity(chatId, fullHistory = true)))
        every { AppLogRecorder.captureForExport(any(), any()) } answers { error("The ring no longer contains the original failure") }
        readSection = { _, before, _ -> before() }
        val resumed = capture(resume = true)
        assertEquals(2, captureCount)
        assertTrue(archive(resumed).getValue("critical-app-logs.jsonl").contains("tool-failure"))
        assertEquals(JsonPrimitive(true), stage(resumed, "critical_logs")["reusedCheckpoint"])
    }

    @Test fun failedDestinationCopyDeliversItsPendingArchiveBeforeCapturingAnotherPart() = runBlocking {
        destination.writeText("destination is temporarily unavailable")
        assertTrue(runCatching { capture() }.isFailure)
        val work = DiagnosticCaptureStorage.directory(context, chatId)
        val identity = diagnosticCaptureIdentity(chatId, fullHistory = true)
        val session = requireNotNull(StagedDiagnosticCapture.findResumableSession(work, identity))
        val pending = requireNotNull(StagedDiagnosticCapture.pendingArchive(work, identity, session))
        val manifest = pending.manifest
        assertEquals(JsonPrimitive(1L), manifest["archivePart"])
        every { AppLogRecorder.captureForExport(any(), any()) } answers { error("Pending archive handoff must not recapture logs") }
        readSection = { _, _, _ -> error("Pending archive handoff must not advance history") }
        assertTrue(destination.delete())
        val delivered = capture(resume = true)
        assertEquals(manifest, delivered.manifest)
        assertEquals(2, captureCount)
        assertTrue(delivered.file.isFile)
        assertEquals(delivered.manifest, StagedDiagnosticCapture.verify(delivered.file))
        assertEquals(null, StagedDiagnosticCapture.pendingArchive(work, identity, session))
    }

    @Test fun recentMetadataWithoutLogEvidenceIsExplicitlyPartial() = runBlocking {
        retainRecentLogs = false
        val result = capture()
        val recent = stage(result, "recent_logs")
        assertEquals(JsonPrimitive("COMPLETED"), stage(result, "critical_logs")["status"])
        assertEquals(JsonPrimitive("PARTIAL"), recent["status"])
        assertEquals(JsonPrimitive(0), recent["evidenceRecords"])
        assertEquals(JsonPrimitive(1), (recent["omissions"] as JsonObject)["NO_RETAINED_APP_LOGS"])
        assertTrue(archive(result).getValue("critical-app-logs.jsonl").contains("tool-failure"))
    }

    @Test fun temporaryCaptureLeavesOnlyTheRequestedArchive() = runBlocking {
        val result = capture(temporary = true)
        assertTrue(result.file.isFile)
        assertEquals(JsonPrimitive(false), result.manifest["resumable"])
        assertFalse(DiagnosticCaptureStorage.directory(context, chatId).exists())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("temporary-diagnostic-capture-") })
        assertFalse(diagnosticExportSummary(result.manifest).contains("Resume diagnostic capture"))
    }

    @Test fun deletingChatDuringCaptureInvalidatesReusedAndNewFiles() = runBlocking {
        readSection = { section, before, _ ->
            if (section == "tool_receipts") DiagnosticCaptureStorage.deleteChat(context, chatId)
            before()
        }
        assertTrue(runCatching { capture() }.exceptionOrNull() is CancellationException)
        assertFalse(DiagnosticCaptureStorage.directory(context, chatId).walkTopDown().any(File::isFile))
        assertFalse(destination.walkTopDown().any(File::isFile))
    }

    @Test fun adapterChunksLargeEscapedUnicodeAfterRedactionAndMarksOversizedSourceIncomplete() = runBlocking {
        val content = "x".repeat(147_734) + "private-secret" + "\"\\\u0001😀\n".repeat(20_000)
        readSection = { section, before, consume ->
            before()
            if (section == "conversation") consume(buildJsonObject { put("content", content) })
        }
        val result = capture()
        val lines = archive(result).getValue("conversation.jsonl").lineSequence().filter { it.isNotBlank() }.toList()
        val chunks = lines.map { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }
        assertTrue(chunks.size > 1)
        assertTrue(lines.all { it.toByteArray().size <= 512 * 1024 })
        assertFalse(lines.any { "private-secret" in it })
        chunks.forEach { assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256((it["content"] as JsonPrimitive).content), (it["chunkSha256"] as JsonPrimitive).content) }
        val assembled = chunks.joinToString("") { (it["content"] as JsonPrimitive).content }
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256(assembled), (chunks.first()["recordSha256"] as JsonPrimitive).content)
        val decoded = kotlinx.serialization.json.Json.parseToJsonElement(assembled) as JsonObject
        assertEquals(content.replace("private-secret", "[redacted]"), (decoded["content"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive("COMPLETED"), stage(result, "conversation")["status"])
        readSection = { section, before, consume ->
            before()
            if (section == "conversation") consume(buildJsonObject { put("content", "x".repeat(1_048_577)) })
        }
        val omitted = capture()
        assertEquals(JsonPrimitive("PARTIAL"), stage(omitted, "conversation")["status"])
        assertTrue(stage(omitted, "conversation")["omissions"].toString().contains("RECORD_CHARACTER_LIMIT"))
    }

    private suspend fun capture(temporary: Boolean = false, resume: Boolean = false): DiagnosticCaptureResult = writeStagedChatDiagnostics(
        context, destination, ChatRoomV2(chatId, "Test", isTemporary = temporary),
        listOf(PlatformV2(name = "Profile", token = "private-secret", systemPrompt = "Prompt private-secret")),
        ChatMcpToolConfig(), AppFeatureSettings(), "draft private-secret", emptyList(), database, null,
        reducedMemory = false, resume = resume, fullHistory = true, stillAuthorized = {}
    )

    private fun archive(result: DiagnosticCaptureResult): Map<String, String> = ZipFile(result.file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).reader().use { reader -> reader.readText() } } }
    private fun stage(result: DiagnosticCaptureResult, id: String) = (result.manifest.getValue("stages") as JsonArray).filterIsInstance<JsonObject>().single { it["id"] == JsonPrimitive(id) }
}
