package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AndroidDiagnosticExportMemory
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.CapturedJournalTimeline
import dev.chungjungsoo.gptmobile.data.diagnostics.CapturedJournalTimelineFailure
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureLogSnapshot
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureOptions
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureResult
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureStage
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticCaptureStorage
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticDatabaseSource
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticExportGate
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticLogSnapshot
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticStageSink
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticTimeline
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticValueProjector
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.StagedDiagnosticCapture
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal fun diagnosticCaptureIdentity(chatId: Int): String = "${BuildConfig.APPLICATION_ID}:$chatId:${BuildConfig.APP_COMMIT}:staged-v4"

/** UI snapshots are small references; history comes from bounded database/file producers. */
internal suspend fun writeStagedChatDiagnostics(
    context: Context,
    destinationDirectory: File,
    room: ChatRoomV2,
    profiles: List<PlatformV2>,
    tools: ChatMcpToolConfig,
    features: AppFeatureSettings,
    draft: String,
    liveMessages: List<MessageV2>,
    database: DiagnosticDatabaseSource,
    research: ResearchSessionStore?,
    reducedMemory: Boolean,
    resume: Boolean,
    workspace: dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository? = null,
    stillAuthorized: () -> Unit
): DiagnosticCaptureResult = DiagnosticExportGate.run {
    withContext(Dispatchers.IO) {
        val guard = DiagnosticCaptureStorage.guard(room.id)
        val workDirectory = if (room.isTemporary) File(context.cacheDir, "temporary-diagnostic-capture-${UUID.randomUUID()}") else DiagnosticCaptureStorage.directory(context, room.id)
        val identity = diagnosticCaptureIdentity(room.id)
        var sessionDirectory: File? = null
        val options = DiagnosticCaptureOptions(
            reducedMemory = reducedMemory,
            minimumHeadroomBytes = if (reducedMemory) 4L * 1024 * 1024 else 16L * 1024 * 1024,
            memorySampler = AndroidDiagnosticExportMemory::sample,
            guard = {
                guard()
                stillAuthorized()
            },
            onSessionReady = { sessionDirectory = it },
            traceStage = { stage ->
                val trace = dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace.begin("diagnostic_$stage", dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner(screen = "DiagnosticExport"))
                val finish: () -> Unit = { dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace.end(trace) }
                finish
            },
            resumable = !room.isTemporary,
            deferArchiveHandoff = !room.isTemporary
        )
        val maximumCharacters = if (reducedMemory) 12 * 1024 else 96 * 1024
        // A single measured source row may exceed one JSONL record; redact it once, then chunk.
        val maximumSourceCharacters = if (reducedMemory) 256 * 1024 else 1024 * 1024
        val json = Json { encodeDefaults = true }
        val redactor = dev.chungjungsoo.gptmobile.data.diagnostics.ChatDebugRedactor(profiles.mapNotNull { it.token })
        val logs = mutableMapOf<Boolean, DiagnosticLogSnapshot>()
        var publishedFile: File? = null
        fun authorized() {
            guard()
            stillAuthorized()
        }
        suspend fun DiagnosticStageSink.emit(value: kotlinx.serialization.json.JsonElement, evidence: Boolean = true, projected: Boolean = false) {
            logicalRecord {
                authorized()
                checkBudget()
                // Database cells already share one bounded projector. Do not parse or expand them again.
                val bounded = if (projected) value else DiagnosticValueProjector(maximumSourceCharacters) { omit(it) }.element(value)
                val redacted = redactor.redact(bounded).toString()
                if (redacted.length <= maximumCharacters / 2) {
                    record(redacted, evidence)
                } else {
                    // Escaping can expand a character six-fold; leave space for envelope metadata.
                    DiagnosticRecordChunks.encode(redacted, maximumCharacters / 8).forEach { record(it.toString(), evidence) }
                }
            }
        }
        suspend fun captureLogs(sink: DiagnosticStageSink, critical: Boolean) {
            authorized()
            val snapshot = logs[critical] ?: DiagnosticCaptureLogSnapshot.open(
                checkNotNull(sessionDirectory),
                beforeRead = {
                    authorized()
                    sink.checkBudget()
                },
                redactLine = { line, omitted ->
                    val bounded = DiagnosticValueProjector(maximumCharacters, omitted).text(line)
                    if (bounded is JsonPrimitive && bounded.content.startsWith("[diagnostic value omitted:")) {
                        null
                    } else {
                        val redacted = redactor.redact(bounded)
                        if (redacted is JsonPrimitive && redacted.isString) redacted.content else redacted.toString()
                    }
                },
                capture = { AppLogRecorder.captureForExport(it, reducedMemory) },
                critical = critical
            ).also { logs[critical] = it }
            sink.note(
                buildJsonObject {
                    put("kind", "log_capture_metadata")
                    put("capturedAtMillis", snapshot.capturedAtMillis)
                    put("trackingEnabled", snapshot.trackingEnabled)
                    put("droppedRecords", snapshot.droppedRecords)
                    put("droppedCriticalRecords", snapshot.droppedCriticalRecords)
                    put("retainedLogRecords", snapshot.recordCount)
                    put("omissionReason", snapshot.omissionReason)
                }.toString()
            )
            snapshot.omissionReason?.let { sink.omit(it) }
            snapshot.sourceFailures.filterKeys { it == "pending-records" || it.startsWith("critical") == critical }.values.forEach { sink.omit(it) }
            snapshot.sources.filter { it.name.startsWith("critical") == critical }.forEach { source ->
                if (source.skippedSourceBytes > 0) sink.omit("LOG_SOURCE_WINDOW_LIMIT")
                if (source.truncatedRecords > 0) sink.omit("LOG_RECORD_TRUNCATED", source.truncatedRecords)
                DiagnosticCaptureLogSnapshot.lines(source.file, {
                    authorized()
                    sink.checkBudget()
                }) { line ->
                    sink.emit(
                        buildJsonObject {
                            put("source", source.name)
                            put("line", line)
                        }
                    )
                }
            }
        }
        val stages = mutableListOf(
            DiagnosticCaptureStage("latest_state", "latest-state.jsonl", refreshOnResume = true) { sink ->
                sink.emit(
                    buildJsonObject {
                        put("kind", "latest_state_projection")
                        put("scope", "Latest retained run verdict, accepted policy fragments, tool receipt, pending work, and GitHub verification/review/budget/output/task checkpoints. Full history follows in independently cursor-backed stages.")
                        put("capturedAtMillis", System.currentTimeMillis())
                    },
                    evidence = false
                )
                database.capture(room.id, "latest_state", maximumCharacters, {
                    authorized()
                    sink.checkBudget()
                }, { sink.emit(it, projected = true) }, { sink.omit(it) }, maximumSourceBytes = maximumSourceCharacters, metadata = { sink.emit(it, evidence = false, projected = true) })
            },
            DiagnosticCaptureStage("critical_logs", "critical-app-logs.jsonl", critical = true, lowMemorySafe = true, emptyReason = "NO_RETAINED_CRITICAL_LOGS") { captureLogs(it, critical = true) },
            DiagnosticCaptureStage("capture_context", "capture-context.jsonl", lowMemorySafe = true, content = dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticStageContent.CONFIGURATION) { sink ->
                sink.emit(
                    buildJsonObject {
                        put("schema", "gptmobile.diagnostic-context.v1")
                        put("chatId", room.id)
                        put("version", BuildConfig.VERSION_NAME)
                        put("build", BuildConfig.VERSION_CODE)
                        put("appCommit", BuildConfig.APP_COMMIT)
                        put("buildVariant", BuildConfig.BUILD_TYPE)
                        put("capturedAtMillis", System.currentTimeMillis())
                        put("representation", "Independent JSONL sections; database field names and bounded decoded storage values. Combined text is retained as durable blocks.")
                        put("consistency", "Each stage has its own timestamp and table high-watermark. Live changes and resumed stages are not a transaction snapshot.")
                        put("recordChunking", "diagnostic_record_chunk records reassemble by recordId/chunkIndex; verify each chunkSha256 and the recordSha256 over concatenated UTF-8 content before parsing JSON.")
                        put("captureParts", "All chunks of a logical record stay in one archive part. Resume advances frozen database table cursors and references earlier archive/section digests; retain previous parts to reconstruct complete captured history.")
                        put("timelineKeys", "Journal eventId/runId/invocationId and database run_id/parentRunId/call_id join source records. Sequence orders each journal stream; stage intervals qualify cross-section comparisons.")
                        put("drawTimingScope", "Measured FIRST_VISIBLE_TEXT records observe Compose drawContent completion for the owned run/fence/part, with outputOrigin, processSession and monotonicNanos. Exact-byte provenance may link a model invocation; provider-to-draw timing requires MODEL_TEXT plus matching INVOCATION_STARTED and FIRST_TEXT in the same process. System errors, progress notices and ambiguous output are not provider latency. Draw completion does not confirm display presentation; per-HTTP first_ui_paint_ms remains unmeasured without explicit lineage.")
                        put("sourceDigests", "Record digests describe their original stored representations. Credential redaction can change text; stage file hashes certify the bytes in this export.")
                        put("logSnapshotScope", "Logs are frozen independently per log section and capture session, then retained for interrupted stages. Resume does not replace captured evidence after ring rotation. Start a new capture to collect newer logs.")
                        put("profileScope", "Current configured profiles; actual routing evidence is in runs and invocations, not inferred from settings.")
                        put("memoryMeasurements", "Native allocated heap and ART counters where available; null does not mean zero.")
                        put("privacy", "Credentials are redacted; this user-requested export can contain private conversation text.")
                    },
                    evidence = false
                )
                sink.emit(
                    buildJsonObject {
                        put("conversation", json.encodeToJsonElement(room.copy(draftText = null, draftAttachments = "[]", lastShareToken = null)))
                        put("chatTools", json.encodeToJsonElement(tools))
                        put("draftAttachments", "Attachment contents are not captured by this diagnostic format.")
                    },
                    evidence = false
                )
                if (draft.length <= maximumCharacters) sink.emit(buildJsonObject { put("composerDraft", draft) }, evidence = false) else sink.omit("COMPOSER_DRAFT_CHARACTER_LIMIT")
            },
            DiagnosticCaptureStage("recent_logs", "recent-app-logs.jsonl", lowMemorySafe = true, emptyReason = "NO_RETAINED_APP_LOGS") { captureLogs(it, critical = false) },
            DiagnosticCaptureStage("profiles", "profiles.jsonl", lowMemorySafe = true) { sink ->
                profiles.forEach { profile ->
                    sink.checkBudget()
                    val characters = profile.systemPrompt.orEmpty().length.toLong() + profile.openRouterRouting.orEmpty().length + profile.ollamaOptions.orEmpty().length + profile.labels.orEmpty().length
                    if (characters > maximumCharacters) {
                        sink.omit("PROFILE_SETTINGS_CHARACTER_LIMIT")
                        sink.emit(json.encodeToJsonElement(profile.copy(token = null, secretRef = null, systemPrompt = "[omitted: character limit]", openRouterRouting = null, ollamaOptions = null, labels = null)))
                    } else {
                        sink.emit(json.encodeToJsonElement(profile.copy(token = null, secretRef = null)))
                    }
                }
            },
            DiagnosticCaptureStage("tool_catalog", "tool-catalog.jsonl", lowMemorySafe = true, refreshOnResume = true) { sink ->
                // Current process inventories do not stand in for a past run's admissions.
                dev.chungjungsoo.gptmobile.data.agent.ToolCatalogDiagnostics.snapshot().filter { it["kind"] != JsonPrimitive("run_tool_admission") }.forEach { row ->
                    sink.emit(row, evidence = row["kind"] == JsonPrimitive("tool_catalog_stage"))
                }
                if (workspace == null) {
                    sink.omit("RUN_TOOL_ADMISSION_STORE_UNAVAILABLE")
                } else {
                    val records = workspace.dao.githubTaskEvidence(room.id, "tool-admission:")
                    sink.emit(
                        buildJsonObject {
                            put("kind", "run_tool_admission_snapshot_scope")
                            put("availability", if (records.isEmpty()) "NO_RETAINED_RUN_SNAPSHOTS" else "PERSISTED_RUN_SNAPSHOTS")
                            put("retainedRecordCount", minOf(records.size, 4096))
                            put("recordCountTruncated", records.size > 4096)
                            put("scope", "Saved preparation-stage receipts for this conversation. Missing receipts do not prove an empty catalog or successful preparation.")
                        },
                        evidence = false
                    )
                    if (records.size > 4096) sink.omit("RUN_TOOL_ADMISSION_RECORD_LIMIT")
                    records.take(4096).forEach { record ->
                        authorized()
                        sink.checkBudget()
                        val snapshot = workspace.protectedSynthesisCheckpoint(record.id)
                        if (snapshot == null || snapshot["schema"] != JsonPrimitive("gptmobile.run-tool-admission.v1") || snapshot["runId"] != JsonPrimitive(record.runId)) {
                            sink.omit("RUN_TOOL_ADMISSION_SNAPSHOT_UNREADABLE")
                        } else {
                            sink.emit(snapshot)
                        }
                    }
                }
            }
        )
        listOf("tool_receipts", "invocations", "runs", "conversation", "workspace", "answer_segments", "combined_documents", "research_database").forEach { section ->
            stages += DiagnosticCaptureStage(section, "${section.replace('_', '-')}.jsonl", resumableRecords = true) { sink ->
                database.capture(room.id, section, maximumCharacters, {
                    authorized()
                    sink.checkBudget()
                }, { sink.emit(it, projected = true) }, { sink.omit(it) }, maximumSourceBytes = maximumSourceCharacters, metadata = { sink.emit(it, evidence = false, projected = true) }, resumeCursor = sink.resumeCursor, checkpoint = sink::checkpoint)
            }
        }
        stages += DiagnosticCaptureStage("live_messages", "live-messages.jsonl") { sink ->
            liveMessages.forEach { message ->
                sink.checkBudget()
                val characters = message.content.length.toLong() + message.thoughts.length + message.combinedSources.sumOf { it.content.length.toLong() } + message.revisions.sumOf { revision -> revision.content.length.toLong() + revision.thoughts.length }
                if (characters > maximumSourceCharacters) sink.omit("LIVE_MESSAGE_CHARACTER_LIMIT") else sink.emit(json.encodeToJsonElement(message))
            }
        }
        stages += DiagnosticCaptureStage("research", "research.jsonl") { sink ->
            if (research == null) {
                sink.omit("RESEARCH_STORE_UNAVAILABLE")
            } else {
                research.streamDiagnosticRecords(
                    room.id,
                    if (reducedMemory) 32 * 1024 else 128 * 1024,
                    {
                        authorized()
                        sink.checkBudget()
                    },
                    { sink.emit(it) },
                    { sink.omit(it) }
                )
            }
        }
        stages += DiagnosticCaptureStage("run_journal", "run-journal.jsonl") { sink ->
            val journal = RunDiagnostics.journal
            if (journal == null) {
                sink.omit("RUN_JOURNAL_UNAVAILABLE")
            } else {
                val runIds = linkedSetOf<String>()
                database.capture(room.id, "run_index", maximumCharacters, {
                    authorized()
                    sink.checkBudget()
                }, { row ->
                    if ((row["table"] as? JsonPrimitive)?.content == "agent_runs") {
                        val runId = ((row["fields"] as? JsonObject)?.get("run_id") as? JsonPrimitive)?.contentOrNull
                        if (runId == null) {
                            sink.omit("RUN_JOURNAL_ID_UNAVAILABLE")
                        } else {
                            runIds += runId
                        }
                    }
                }, { sink.omit(it) })
                journal.streamRuns(
                    runIds,
                    beforeRead = {
                        authorized()
                        sink.checkBudget()
                    },
                    consume = { sink.emit(json.encodeToJsonElement(it)) }
                ) { runId, coverage ->
                    sink.emit(
                        buildJsonObject {
                            put("kind", "journal_coverage")
                            put("runId", runId)
                            (json.encodeToJsonElement(coverage) as JsonObject).forEach { (key, value) -> put(key, value) }
                        },
                        evidence = false
                    )
                    if (coverage.missingBatches > 0 || coverage.missingSequences > 0 || coverage.storageFailed || coverage.unreadableCheckpoints > 0) sink.omit("RUN_JOURNAL_COVERAGE_GAPS")
                    if (coverage.unknownTailStreams > 0) sink.omit("LEGACY_JOURNAL_TAIL_UNKNOWN")
                }
            }
        }
        stages += DiagnosticCaptureStage("timeline", "timeline.jsonl", refreshOnResume = true) { sink ->
            sink.emit(
                buildJsonObject {
                    put("kind", "timeline_coverage")
                    put("sources", "agent_runs,tool_events,model_invocations,run_journal")
                    put("ordering", "Streaming source order; sort timestampMillis then eventId for display. Journal streamId/sequence is authoritative within each stream.")
                    put("consistency", "Database sources have independent frozen row boundaries. Journal observations are projected from the exact verified run-journal.jsonl in this capture, without reopening the live journal. A terminal parent with a RUNNING child requires lifecycle reconciliation; it is not proof of a stuck request.")
                },
                evidence = false
            )
            database.capture(room.id, "timeline", maximumCharacters, {
                authorized()
                sink.checkBudget()
            }, { row ->
                val event = DiagnosticTimeline.database(row, System.currentTimeMillis())
                if (event == null) sink.omit("TIMELINE_SOURCE_UNAVAILABLE") else sink.emit(event)
            }, { sink.omit(it) }, metadata = { sink.emit(it, evidence = false) })
            try {
                // One rollback boundary prevents a damaged captured journal from
                // contributing apparently valid timeline events before its final hash check.
                sink.logicalRecord {
                    val coverage = CapturedJournalTimeline.stream(
                        checkNotNull(sessionDirectory),
                        beforeRead = {
                            authorized()
                            sink.checkBudget()
                        },
                        consume = { row, evidence -> sink.emit(row, evidence, projected = true) },
                        omitted = { sink.omit(it) }
                    )
                    sink.emit(coverage, evidence = false, projected = true)
                }
            } catch (failure: CapturedJournalTimelineFailure) {
                sink.omit(failure.code)
            }
        }
        stages += DiagnosticCaptureStage("runtime_incidents", "runtime-incidents.jsonl", lowMemorySafe = true, emptyReason = "NO_RETAINED_RUNTIME_INCIDENTS") { sink ->
            val captured = LocalRuntimeIncidentHistory.capture()
            sink.emit(captured.metadata, evidence = false)
            if (captured.availability in setOf("UNAVAILABLE", "FAILED", "PARTIAL")) sink.omit("RUNTIME_INCIDENT_CAPTURE_${captured.availability}")
            captured.records.forEach { sink.emit(it) }
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot(room.id).forEach { sink.emit(json.encodeToJsonElement(it)) }
        }
        stages += DiagnosticCaptureStage("feature_settings", "feature-settings.jsonl", content = dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticStageContent.CONFIGURATION) { sink ->
            sink.emit(
                buildJsonObject {
                    put("diagnosticsCollection", features.diagnosticsCollection)
                    put("localAssistPolicy", json.encodeToJsonElement(features.localAssistPolicy))
                    put("configurationEvidence", "CURRENT_SNAPSHOT_NOT_EXECUTION_PROOF")
                    put("currentChatReasoningOverride", json.encodeToJsonElement(tools.reasoning ?: features.conversationReasoning[room.id]))
                    put(
                        "reasoningByProfile",
                        buildJsonObject {
                            profiles.filter { it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LLAMA }.forEach { profile ->
                                put(
                                    profile.uid,
                                    buildJsonObject {
                                        put("legacyEnabled", profile.reasoning)
                                        put("explicitMode", json.encodeToJsonElement(features.llamaReasoningModes[profile.uid]))
                                        put("resolvedPreference", dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy.preference(profile.reasoning, features.llamaReasoningModes[profile.uid], tools.reasoning ?: features.conversationReasoning[room.id]).name)
                                        put("observedComputation", "NOT_MEASURED_BY_SETTINGS")
                                    }
                                )
                            }
                        }
                    )
                    put(
                        "engineeringGatewayByProfile",
                        buildJsonObject {
                            profiles.forEach { profile -> put(profile.uid, json.encodeToJsonElement(features.githubProfileOverrides[profile.uid]?.engineeringGatewayProfileUid)) }
                        }
                    )
                    put("scope", "Current diagnostic, local-assist, reasoning and explicit engineering-route settings. Accepted per-run policies are retained in runs.jsonl; observed routing and reasoning require invocation receipts. Unrelated preference histories are excluded.")
                },
                evidence = false
            )
        }
        try {
            authorized()
            val session = if (resume && !room.isTemporary) StagedDiagnosticCapture.findResumableSession(workDirectory, identity) ?: error("No unfinished diagnostic capture is available for this conversation.") else null
            if (session != null) sessionDirectory = File(workDirectory, "diagnostic-capture-checkpoints/$session")
            val result = session?.let { StagedDiagnosticCapture.pendingArchive(workDirectory, identity, it) }
                ?: StagedDiagnosticCapture.capture(workDirectory, identity, stages, options, session)
            authorized()
            check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory)
            val destination = File(destinationDirectory, result.file.name)
            val partial = File(destinationDirectory, "${destination.name}.partial")
            try {
                FileOutputStream(partial).use { output ->
                    result.file.inputStream().use { source -> source.copyTo(output, options.bufferBytes) }
                    output.fd.sync()
                }
                authorized()
                check(partial.renameTo(destination)) { "Unable to publish diagnostic archive" }
                publishedFile = destination
                if (room.isTemporary) result.file.delete() else StagedDiagnosticCapture.acknowledgeArchiveHandoff(workDirectory, result)
                result.copy(file = destination)
            } finally {
                partial.delete()
            }
        } finally {
            logs.values.forEach { it.close() }
            // Ordinary coroutine cancellation retains checkpoints. A privacy/context
            // invalidation must also remove files created during the final clear race.
            val invalidation = runCatching { authorized() }.exceptionOrNull()
            if (invalidation != null) {
                publishedFile?.delete()
                sessionDirectory?.let { session ->
                    session.deleteRecursively()
                    workDirectory.listFiles().orEmpty().filter { it.isFile && session.name in it.name }.forEach { it.delete() }
                }
            }
            if (room.isTemporary) workDirectory.deleteRecursively()
            if (invalidation != null) throw invalidation
        }
    }
}
