package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    val credentials = profiles.mapNotNull { it.token }
    val dependencyIds = buildSet {
        addAll(room.activePlatform)
        features.localAssistPolicy.profileUid?.let(::add)
        messages.forEach { message ->
            message.platformType?.let(::add)
            message.combinedSources.forEach { add(it.platformUid) }
            (message.timeline + message.revisions.flatMap { it.timeline }).mapNotNull { it.assistDiagnostic?.profileUid }.forEach(::add)
        }
        (records["invocations"] as? JsonArray).orEmpty().forEach { row ->
            ((row as? JsonObject)?.get("profileUid") as? JsonPrimitive)?.content?.let(::add)
        }
    }
    fun exportedBody(text: String): String = (redactChatDebugData(kotlinx.serialization.json.JsonPrimitive(text), credentials) as kotlinx.serialization.json.JsonPrimitive).content
    val combinedAnalysis = messages.filter { it.isCombinedSynthesis() }.map { message ->
        val answer = ConversationSubject.withoutMetadata(message.effectiveContent())
        buildJsonObject {
            put("messageId", message.id)
            put("runId", message.effectiveRunId())
            put("answerCharacters", answer.length)
            put("answerWords", Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?").findAll(answer).count())
            put("sourceCount", message.combinedSources.size)
            put(
                "profileAttributionSpans",
                JsonArray(dev.chungjungsoo.gptmobile.data.agent.combinedWordingSpans(answer, message.combinedSources).map { span ->
                    buildJsonObject {
                        put("startUtf16", span.start)
                        put("endUtf16", span.end)
                        put("profileUids", JsonArray(span.profileUids.sorted().map { JsonPrimitive(it) }))
                        put("text", exportedBody(answer.substring(span.start, span.end)))
                    }
                })
            )
            put("sources", JsonArray(message.combinedSources.map { source ->
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
            }))
            message.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality?.let { quality ->
                put("qualityReport", json.encodeToJsonElement(quality))
            }
            put("coverageLimit", "Deterministic lexical coverage hints only; paraphrase matching does not establish factual accuracy or whether omitted details were in scope.")
        }
    }
    val raw = buildJsonObject {
        put("schema", "gptmobile.conversation.debug.v4")
        put(
            "schemaVersions",
            buildJsonObject {
                put("conversation", 3)
                put("combinedAnalysis", 1)
                put("appLogSnapshot", 1)
                put("runEvents", 5)
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
        put("messages", json.encodeToJsonElement(messages))
        put("combinedAnalysis", JsonArray(combinedAnalysis))
        put(
            "appLogSnapshot",
            JsonArray(appLogSnapshot.takeLast(500).map { entry ->
                buildJsonObject {
                    put("time", entry.time)
                    put("level", entry.level)
                    put("tag", entry.tag)
                    put("eventId", entry.eventId)
                    put("message", exportedBody(entry.message))
                }
            })
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
                put(
                    key,
                    JsonArray(
                        value.map { element ->
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
                    )
                )
            } else {
                put(key, value)
            }
        }
        put(
            "research",
            JsonArray(
                research.map { session ->
                    buildJsonObject {
                        put("runId", session.runId)
                        put("chatId", session.chatId)
                        put("evidence", session.snapshot.json())
                    }
                }
            )
        )
        put(
            "responseMapping",
            JsonArray(
                messages.filter { it.platformType != null }.map { message ->
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
            )
        )
    }
    return json.encodeToString(JsonElement.serializer(), redactChatDebugData(raw, credentials))
}

internal fun debugBodyDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
