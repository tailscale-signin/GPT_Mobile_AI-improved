package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AndroidDiagnosticExportMemory
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
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
import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import java.io.File
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

internal fun diagnosticCaptureIdentity(chatId: Int): String = "${BuildConfig.APPLICATION_ID}:$chatId:${BuildConfig.APP_COMMIT}:staged-v2"

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
            resumable = !room.isTemporary
        )
        val maximumCharacters = if (reducedMemory) 12 * 1024 else 96 * 1024
        // A single measured source row may exceed one JSONL record; redact it once, then chunk.
        val maximumSourceCharacters = if (reducedMemory) 256 * 1024 else 1024 * 1024
        val json = Json { encodeDefaults = true }
        val credentials = profiles.mapNotNull { it.token }
        val logs = mutableMapOf<Boolean, DiagnosticLogSnapshot>()
        var publishedFile: File? = null
        fun authorized() {
            guard()
            stillAuthorized()
        }
        suspend fun DiagnosticStageSink.emit(value: kotlinx.serialization.json.JsonElement, evidence: Boolean = true) {
            authorized()
            checkBudget()
            val bounded = DiagnosticValueProjector(maximumSourceCharacters) { omit(it) }.element(value)
            val redacted = redactChatDebugData(bounded, credentials).toString()
            if (redacted.length <= maximumCharacters / 2) {
                record(redacted, evidence)
            } else {
                // Escaping can expand a character six-fold; leave space for envelope metadata.
                DiagnosticRecordChunks.encode(redacted, maximumCharacters / 8).forEach { record(it.toString(), evidence) }
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
                        val redacted = redactChatDebugData(bounded, credentials)
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
            DiagnosticCaptureStage("critical_logs", "critical-app-logs.jsonl", critical = true, lowMemorySafe = true, emptyReason = "NO_RETAINED_CRITICAL_LOGS") { captureLogs(it, critical = true) },
            DiagnosticCaptureStage("capture_context", "capture-context.jsonl", lowMemorySafe = true) { sink ->
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
                        put("timelineKeys", "Journal eventId/runId/invocationId and database run_id/parentRunId/call_id join source records. Sequence orders each journal stream; stage intervals qualify cross-section comparisons.")
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
            }
        )
        listOf("tool_receipts", "invocations", "runs", "conversation", "workspace", "answer_segments", "combined_documents", "research_database").forEach { section ->
            stages += DiagnosticCaptureStage(section, "${section.replace('_', '-')}.jsonl") { sink ->
                database.capture(room.id, section, maximumCharacters, {
                    authorized()
                    sink.checkBudget()
                }, { sink.emit(it) }, { sink.omit(it) }, maximumSourceBytes = maximumSourceCharacters, metadata = { sink.emit(it, evidence = false) })
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
                database.capture(room.id, "run_index", maximumCharacters, {
                    authorized()
                    sink.checkBudget()
                }, { row ->
                    if ((row["table"] as? JsonPrimitive)?.content == "agent_runs") {
                        val runId = ((row["fields"] as? JsonObject)?.get("run_id") as? JsonPrimitive)?.contentOrNull
                        if (runId == null) {
                            sink.omit("RUN_JOURNAL_ID_UNAVAILABLE")
                        } else {
                            val coverage = journal.stream(runId, {
                                authorized()
                                sink.checkBudget()
                            }) { sink.emit(json.encodeToJsonElement(it)) }
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
                }, { sink.omit(it) })
            }
        }
        stages += DiagnosticCaptureStage("timeline", "timeline.jsonl") { sink ->
            sink.emit(
                buildJsonObject {
                    put("kind", "timeline_coverage")
                    put("sources", "agent_runs,tool_events,model_invocations,run_journal")
                    put("ordering", "Streaming source order; sort timestampMillis then eventId for display. Journal streamId/sequence is authoritative within each stream.")
                    put("consistency", "Each source is observed independently at a frozen row or sequence boundary. A terminal parent with a RUNNING child requires lifecycle reconciliation; it is not proof of a stuck request.")
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
            val journal = RunDiagnostics.journal
            if (journal == null) {
                sink.omit("RUN_JOURNAL_UNAVAILABLE")
            } else {
                database.capture(room.id, "run_index", maximumCharacters, {
                    authorized()
                    sink.checkBudget()
                }, { row ->
                    val runId = ((row["fields"] as? JsonObject)?.get("run_id") as? JsonPrimitive)?.contentOrNull
                    if (runId == null) {
                        sink.omit("RUN_JOURNAL_ID_UNAVAILABLE")
                    } else {
                        val coverage = journal.stream(runId, {
                            authorized()
                            sink.checkBudget()
                        }) { sink.emit(DiagnosticTimeline.journal(it)) }
                        if (coverage.missingSequences > 0 || coverage.missingBatches > 0 || coverage.unreadableCheckpoints > 0 || coverage.unknownTailStreams > 0 || coverage.storageFailed) sink.omit("TIMELINE_JOURNAL_COVERAGE_INCOMPLETE")
                    }
                }, { sink.omit(it) })
            }
        }
        stages += DiagnosticCaptureStage("runtime_incidents", "runtime-incidents.jsonl", lowMemorySafe = true, emptyReason = "NO_RETAINED_RUNTIME_INCIDENTS") { sink ->
            val captured = LocalRuntimeIncidentHistory.capture()
            sink.emit(captured.metadata, evidence = false)
            if (captured.availability in setOf("UNAVAILABLE", "FAILED", "PARTIAL")) sink.omit("RUNTIME_INCIDENT_CAPTURE_${captured.availability}")
            captured.records.forEach { sink.emit(it) }
            dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot(room.id).forEach { sink.emit(json.encodeToJsonElement(it)) }
        }
        stages += DiagnosticCaptureStage("feature_settings", "feature-settings.jsonl") { sink ->
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
            val result = StagedDiagnosticCapture.capture(workDirectory, identity, stages, options, session)
            authorized()
            check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory)
            val destination = File(destinationDirectory, result.file.name)
            val partial = File(destinationDirectory, "${destination.name}.partial")
            try {
                result.file.inputStream().use { source -> partial.outputStream().buffered(options.bufferBytes).use { source.copyTo(it, options.bufferBytes) } }
                authorized()
                check(partial.renameTo(destination)) { "Unable to publish diagnostic archive" }
                publishedFile = destination
                result.file.delete()
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
