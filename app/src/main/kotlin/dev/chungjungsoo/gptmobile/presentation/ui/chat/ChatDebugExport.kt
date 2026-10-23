package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal fun buildChatDebugExport(
    room: ChatRoomV2,
    messages: List<MessageV2>,
    profiles: List<PlatformV2>,
    tools: ChatMcpToolConfig,
    features: AppFeatureSettings,
    draft: String,
    records: JsonObject,
    research: List<ResearchSession>,
    json: Json,
    appLogSnapshot: List<AppLogEntry> = emptyList()
): String {
    val output = java.io.StringWriter()
    kotlinx.coroutines.runBlocking { writeChatDebugExport(room, messages, profiles, tools, features, draft, records, research, json, appLogSnapshot, output) }
    return output.toString()
}

internal suspend fun writeChatDebugExport(
    room: ChatRoomV2,
    messages: List<MessageV2>,
    profiles: List<PlatformV2>,
    tools: ChatMcpToolConfig,
    features: AppFeatureSettings,
    draft: String,
    records: JsonObject,
    research: List<ResearchSession>,
    json: Json,
    appLogSnapshot: List<AppLogEntry> = emptyList(),
    destination: java.io.Writer,
    researchStream: suspend (suspend (ResearchSession) -> Unit) -> Unit = { consume -> research.forEach { consume(it) } }
) {
    val credentials = profiles.mapNotNull { it.token }
    val dependencyIds = buildSet {
        addAll(room.activePlatform)
        features.localAssistPolicy.profileUid?.let(::add)
        messages.forEach { message ->
            message.platformType?.let(::add)
            (message.combinedSources + message.revisions.flatMap { it.combinedSources }).forEach { add(it.platformUid) }
            (message.timeline + message.revisions.flatMap { it.timeline }).mapNotNull { it.assistDiagnostic?.profileUid }.forEach(::add)
        }
        (records["invocations"] as? JsonArray).orEmpty().forEach { row ->
            ((row as? JsonObject)?.get("profileUid") as? JsonPrimitive)?.content?.let(::add)
        }
    }
    fun exportedBody(text: String): String = (redactChatDebugData(kotlinx.serialization.json.JsonPrimitive(text), credentials) as kotlinx.serialization.json.JsonPrimitive).content
    val combinedAnalysis = messages.asSequence().filter { it.effectiveRunId()?.startsWith("combined-synthesis:") == true || it.effectiveCombinedSources().isNotEmpty() }.map { message ->
        val answer = ConversationSubject.withoutMetadata(message.effectiveContent())
        val sources = message.effectiveCombinedSources()
        buildJsonObject {
            put("messageId", message.id)
            put("runId", message.effectiveRunId())
            put("answerCharacters", answer.length)
            put("answerWords", dev.chungjungsoo.gptmobile.data.agent.visibleWordCount(answer))
            put("wordCounterVersion", dev.chungjungsoo.gptmobile.data.agent.CombinedWordCounter.VERSION)
            put("sourceCount", sources.size)
            put(
                "profileAttributionSpans",
                JsonArray(
                    dev.chungjungsoo.gptmobile.data.agent.combinedWordingSpans(answer, sources).map { span ->
                        buildJsonObject {
                            put("startUtf16", span.start)
                            put("endUtf16", span.end)
                            put("profileUids", JsonArray(span.profileUids.sorted().map { JsonPrimitive(it) }))
                            put("text", exportedBody(answer.substring(span.start, span.end)))
                        }
                    }
                )
            )
            put(
                "sources",
                JsonArray(
                    sources.map { source ->
                        val coverage = dev.chungjungsoo.gptmobile.data.agent.CombinedContentCoverage(listOf(source)).check(answer)
                        val missing = coverage.missing
                        buildJsonObject {
                            put("profileUid", source.platformUid)
                            put("profileName", source.platformName)
                            put("completionStatus", source.completionStatus)
                            put("sourceCharacters", source.content.length)
                            put("candidateUnits", coverage.total)
                            put("matchedUnits", coverage.total - missing.size)
                            put("missingUnits", missing.size)
                            put("exactMatches", coverage.exactMatches)
                            put("paraphraseMatches", coverage.paraphraseMatches)
                            put("tentativeMatches", coverage.tentativeMatches)
                            put("missingSamples", JsonArray(missing.take(12).map { JsonPrimitive(exportedBody(it.text).take(240)) }))
                        }
                    }
                )
            )
            message.effectiveTimeline().lastOrNull { it.combinedQuality != null }?.combinedQuality?.let { quality ->
                put("qualityReport", json.encodeToJsonElement(quality))
            }
            put("coverageLimit", "Deterministic lexical coverage hints only; paraphrase matching does not establish factual accuracy or whether omitted details were in scope.")
        }
    }
    DebugJsonStream(destination, credentials).objectBody {
        put("schema", DebugExportSchema.CONVERSATION)
        put(
            "schemaVersions",
            buildJsonObject {
                put("conversation", DebugExportSchema.CONVERSATION_VERSION)
                put("combinedAnalysis", 1)
                put("appLogSnapshot", 1)
                put("runEvents", dev.chungjungsoo.gptmobile.data.diagnostics.RUN_EVENT_SCHEMA)
                put("legacyEvents", 4)
                put("assist", 2)
            }
        )
        put(
            "capture",
            buildJsonObject {
                put("level", if (records["runJournal"] != null) "metadata-and-retained-records" else "retained-records")
                put("capturedFully", false)
                put("replayableReducer", false)
                put("completeProviderWireCapture", false)
                put("exportTruncated", false)
                put("sourceCompleteness", "unknown; inspect each retained artifact")
                put("consistency", "Frozen component snapshots; live overlays are not a database transaction")
                put("highWatermarks", (records["runJournal"] as? JsonObject)?.get("highWatermarks") ?: JsonObject(emptyMap()))
                put(
                    "activeRunIds",
                    JsonArray(
                        (records["runs"] as? JsonArray).orEmpty().mapNotNull { row ->
                            val run = row as? JsonObject ?: return@mapNotNull null
                            if ((run["status"] as? JsonPrimitive)?.content in setOf("QUEUED", "RUNNING", "STOPPING")) run["runId"] else null
                        }
                    )
                )
            }
        )
        put(
            "profileDependencies",
            JsonArray(
                dependencyIds.sorted().map { uid ->
                    buildJsonObject {
                        put("profileUid", uid)
                        put("state", if (profiles.any { it.uid == uid }) "retained" else "missing-or-deleted")
                    }
                }
            )
        )
        put("exportedAt", java.time.Instant.now().toString())
        put("version", BuildConfig.VERSION_NAME)
        put("build", BuildConfig.VERSION_CODE)
        put("package", BuildConfig.APPLICATION_ID)
        put("snapshot", "Stored records with live message overlays; generation may still be running. No history-window limit.")
        put("availability", "Contains retained data only. Credentials are redacted. Attachment bytes, deleted data and unrecorded wire payloads are unavailable. Request receipts include digests and retained context, not a complete wire capture. Estimated token counts remain estimates. Research history may have been evicted by its retention policy.")
        put("conversation", json.encodeToJsonElement(room))
        put("providerFailures", json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.snapshot(room.id)))
        array("messages", messages.asSequence()) { json.encodeToJsonElement(it) }
        array("combinedAnalysis", combinedAnalysis) { it }
        put("localRuntimeIncidents", JsonArray(dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory.snapshot()))
        put(
            "appLogSnapshot",
            JsonArray(
                appLogSnapshot.takeLast(500).map { entry ->
                    buildJsonObject {
                        put("time", entry.time)
                        put("level", entry.level)
                        put("tag", entry.tag)
                        put("eventId", entry.eventId)
                        put("monotonicNanos", entry.monotonicNanos)
                        entry.bootMillis?.let { put("bootMillis", it) }
                        put("processId", entry.processId)
                        put("message", exportedBody(entry.message))
                    }
                }
            )
        )
        put(
            "appLogSnapshotAvailability",
            buildJsonObject {
                put("retainedEvents", appLogSnapshot.size.coerceAtMost(500))
                put("limit", 500)
                put("scope", "Recent sanitized app events; Settings > Debug > Logs can export the full retained log window.")
            }
        )
        put("profiles", json.encodeToJsonElement(profiles.map { it.copy(token = null, secretRef = null) }))
        put("chatTools", json.encodeToJsonElement(tools))
        put("featureSettings", json.encodeToJsonElement(features))
        put("composerDraft", draft)
        records.forEach { (key, value) ->
            if (key == "answerSegments" && value is JsonArray) {
                val previous = mutableMapOf<String, String>()
                array(key, value.asSequence()) { element ->
                    val segment = element as JsonObject
                    val run = (segment["runId"] as JsonPrimitive).content
                    val body = exportedBody((segment["text"] as JsonPrimitive).content)
                    val digest = debugBodyDigest(body)
                    JsonObject(
                        segment.toMutableMap().apply {
                            put("text", JsonPrimitive(body))
                            put("digest", JsonPrimitive(digest))
                            put("previousDigest", previous[run]?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                            put("utf8Bytes", JsonPrimitive(body.toByteArray(Charsets.UTF_8).size))
                            put("digestRepresentation", JsonPrimitive("redacted-export-segment-utf8"))
                            previous[run] = digest
                        }
                    )
                }
            } else {
                put(key, value)
            }
        }
        arrayFrom("research") { emit ->
            researchStream { session ->
                emit(
                    buildJsonObject {
                        put("runId", session.runId)
                        put("chatId", session.chatId)
                        put("turnId", session.turnId)
                        put("authority", session.authority)
                        put("evidence", session.snapshot.json())
                    }
                )
            }
        }
        array("responseMapping", messages.asSequence().filter { it.platformType != null }) { message ->
            buildJsonObject {
                put("messageId", message.id)
                put("userMessageId", message.linkedMessageId)
                put("runId", message.currentRunId)
                put("profileUid", message.platformType)
                put("digestRepresentation", "redacted-export-body-utf8")
                put("bodySha256", debugBodyDigest(exportedBody(message.content)))
                put("selectedBodySha256", debugBodyDigest(exportedBody(message.effectiveContent())))
                put("characters", message.content.length)
                put("activeRevisionIndex", message.activeRevisionIndex)
                put(
                    "revisionMapping",
                    JsonArray(
                        message.revisions.mapIndexed { index, revision ->
                            buildJsonObject {
                                put("index", index)
                                put("runId", revision.runId)
                                put("bodySha256", debugBodyDigest(exportedBody(revision.content)))
                                put(
                                    "contributionMapping",
                                    JsonArray(
                                        revision.combinedSources.mapIndexed { sourceIndex, source ->
                                            buildJsonObject {
                                                put("id", "C${sourceIndex + 1}")
                                                put("profileUid", source.platformUid)
                                                put("bodySha256", debugBodyDigest(exportedBody(source.content)))
                                                put("characters", source.content.length)
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    )
                )
                put(
                    "contributionMapping",
                    JsonArray(
                        message.combinedSources.mapIndexed { index, source ->
                            buildJsonObject {
                                put("id", "C${index + 1}")
                                put("profileUid", source.platformUid)
                                put("bodySha256", debugBodyDigest(exportedBody(source.content)))
                                put("characters", source.content.length)
                            }
                        }
                    )
                )
                put("coverage", "Contribution bodies are supplied for inspection. Inclusion in the prompt does not prove semantic coverage in the final answer.")
            }
        }
    }
}

internal fun debugBodyDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
