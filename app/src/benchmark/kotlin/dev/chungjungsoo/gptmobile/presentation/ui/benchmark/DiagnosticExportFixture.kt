package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureStage
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticDatabaseSource
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticJournal
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import dev.chungjungsoo.gptmobile.data.diagnostics.StagedDiagnosticCapture
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import dev.chungjungsoo.gptmobile.data.security.AndroidSecretVault
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Benchmark-only synthetic records. Production cursor, journal, compression and checkpoint code run unchanged. */
@Composable
internal fun DiagnosticExportFixture() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val chat = remember { Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build() }
    val research = remember { Room.inMemoryDatabaseBuilder(context, ResearchDatabase::class.java).build() }
    val vault = remember { AndroidSecretVault(context, "conversation_export_benchmark", "conversation_export_benchmark_vault") }
    val store = remember { WorkspaceRepository(chat, vault) }
    val database = remember { DiagnosticDatabaseSource(chat, research, store) }
    val journal = remember { RunDiagnosticJournal(vault, scope) }
    val directory = remember { File(context.cacheDir, "export-benchmark-" + UUID.randomUUID()) }
    val runId = remember { UUID.randomUUID().toString() }
    var ready by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(chat, research) {
        onDispose {
            chat.close()
            research.close()
            directory.deleteRecursively()
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            chat.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Synthetic export"))
            repeat(256) { index ->
                store.dao.save(WorkspaceRecord("bench-$index", "benchmark", "Saved result", """{"row":$index,"sample":"${"a".repeat(1024)}"}""", 1, runId))
            }
            journal.clear()
            journal.setRunPrivacy(runId, false)
            repeat(128) { checkNotNull(journal.record(runId, RunDiagnosticKind.TOOL_RESULT, physicalAttempt = it.toLong())) }
            checkNotNull(journal.record(runId, RunDiagnosticKind.RUN_TERMINAL))
        }
        ready = true
    }
    Column(Modifier.semantics { testTagsAsResourceId = true }) {
        if (ready) {
            Button(onClick = {
                ready = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        var journalEvents = 0
                        val stages = listOf(
                            DiagnosticCaptureStage("workspace", "workspace.jsonl", resumableRecords = true) { sink ->
                                database.capture(
                                    1, "workspace", 64 * 1024, sink::checkBudget, { sink.record(it.toString()) }, { sink.omit(it) },
                                    metadata = { sink.note(it.toString()) }, checkpoint = sink::checkpoint, sourceRecord = sink::sourceRecord
                                )
                            },
                            DiagnosticCaptureStage("run_journal", "run-journal.jsonl") { sink ->
                                journal.streamRuns(setOf(runId), sink::checkBudget, {
                                    sink.record(Json.encodeToString(it))
                                    journalEvents++
                                }) { _, coverage ->
                                    sink.note(Json.encodeToString(coverage))
                                }
                            }
                        )
                        if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.beginAsyncSection("conversation.export", 1)
                        val result = try {
                            StagedDiagnosticCapture.capture(directory, runId, stages)
                        } finally {
                            if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.endAsyncSection("conversation.export", 1)
                        }
                        check(result.complete && result.usefulRecords >= 256 && journalEvents > 0 && result.file.isFile)
                        check(java.util.zip.ZipFile(result.file).use { zip -> zip.getEntry("workspace.jsonl") != null && zip.getEntry("run-journal.jsonl") != null })
                    }
                    done = true
                }
            }, modifier = Modifier.testTag("export_ready")) { Text("Export incident") }
        }
        if (done) Text("Export complete", Modifier.testTag("export_complete"))
    }
}
