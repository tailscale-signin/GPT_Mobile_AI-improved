package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
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
    json: Json
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
    val raw = buildJsonObject {
        put("schema", "gptmobile.conversation.debug.v3")
        put(
            "schemaVersions",
            buildJsonObject {
                put("conversation", 3)
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
        put("profiles", json.encodeToJsonElement(profiles.map { it.copy(token = null, secretRef = null) }))
        put("chatTools", json.encodeToJsonElement(tools))
        put("featureSettings", json.encodeToJsonElement(features))
        put("composerDraft", draft)
        records.forEach { (key, value) -> put(key, value) }
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
