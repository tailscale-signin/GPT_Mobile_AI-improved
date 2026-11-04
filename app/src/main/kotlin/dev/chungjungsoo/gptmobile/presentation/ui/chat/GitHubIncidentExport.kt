package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubWorkflowPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Small derived incident index; the original retained records stay in the export. */
internal fun githubIncidentExport(records: JsonObject): JsonObject {
    val decoder = Json { ignoreUnknownKeys = true }
    val rawRuns = records["runs"] as? JsonArray ?: JsonArray(emptyList())
    val rawEvents = records["toolEvents"] as? JsonArray ?: JsonArray(emptyList())
    val runs = rawRuns.mapNotNull { runCatching { decoder.decodeFromJsonElement(AgentRun.serializer(), it) }.getOrNull() }
    val events = rawEvents.mapNotNull { runCatching { decoder.decodeFromJsonElement(ToolEvent.serializer(), it) }.getOrNull() }
    val workspace = (records["workspace"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val verificationRows = workspace.filter { it.text("id")?.let { id -> id.startsWith("github-evidence:") && ":verification_" in id } == true }
        .sortedWith(compareBy({ (it["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0 }, { it.text("id").orEmpty() }))
        .associateBy { it.text("runId") }
    val eventGroups = events.groupBy { it.runId }
    return buildJsonObject {
        put("schemaVersion", 1)
        put("scope", "Retained local run, operation and verification snapshots")
        put("sourceHistoryComplete", false)
        put("sourceHistoryLimitation", "Local records do not establish complete provider wire capture or all remote activity.")
        put("unreadableRunRecords", rawRuns.size - runs.size)
        put("unreadableOperationRecords", rawEvents.size - events.size)
        put(
            "runs",
            JsonArray(
                runs.map { run ->
                    val saved = verificationRows[run.runId]
                    val payload = saved?.text("payload")?.let { runCatching { decoder.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    val verification = payload?.takeUnless { "protectedChunks" in it }
                    val snapshot = GitHubWorkflowPresentation.snapshot(run, eventGroups[run.runId].orEmpty(), events.size == rawEvents.size, verification, saved != null && verification == null)
                    buildJsonObject {
                        put("runId", run.runId)
                        put("userMessageId", run.userMessageId)
                        put("executionStatus", run.status)
                        put("acceptanceStatus", snapshot.acceptance.name)
                        put("failureCategory", snapshot.failureCategory?.let(::JsonPrimitive) ?: JsonNull)
                        put("verificationAvailable", snapshot.verificationAvailable)
                        put("verificationRecordId", saved?.get("id") ?: JsonNull)
                        put("acceptanceLedger", verification?.get("acceptance_ledger") ?: JsonNull)
                        put("evidenceRetentionComplete", snapshot.evidenceComplete?.let(::JsonPrimitive) ?: JsonNull)
                        put("recordedOperationCount", snapshot.observedOperations)
                        put("operationRecordsDecodedCompletely", snapshot.eventWindowComplete)
                        put("recoveryActions", JsonArray(snapshot.recovery.map { JsonPrimitive(it.name) }))
                        put("recoveryPolicy", snapshot.recoveryExplanation)
                        put(
                            "criteria",
                            JsonArray(
                                snapshot.criteria.map { criterion ->
                                    buildJsonObject {
                                        put("requirement", criterion.requirement)
                                        put("status", criterion.status.name)
                                        put("evidence", criterion.detail)
                                    }
                                }
                            )
                        )
                        put(
                            "operationGroups",
                            JsonArray(
                                snapshot.operations.map { group ->
                                    buildJsonObject {
                                        put("label", group.label)
                                        put("observed", group.observed)
                                        put("completed", group.completed)
                                        put("failed", group.failed)
                                        put("pending", group.pending)
                                    }
                                }
                            )
                        )
                    }
                }
            )
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
