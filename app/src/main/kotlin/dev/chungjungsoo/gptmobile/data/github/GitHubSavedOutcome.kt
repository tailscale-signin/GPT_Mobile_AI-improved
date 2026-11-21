package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Local search vocabulary. These are historical observations and user reports, never execution authority. */
internal object GitHubSavedOutcome {
    private val search = Regex("""(?i)^outcome:(build|diagnosis|decision|pr|worked|failed|visual|completed|interrupted|blocked)(?:\s+(.+))?$""")
    private val note = Regex("""(?i)^(?:feedback:\s*(worked|failed)|(?:visual )?evidence:)\s+(ev_[a-f0-9]{24})(?:\s+(.{1,600}))?$""", RegexOption.DOT_MATCHES_ALL)
    private val revision = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
    private val aliases = mapOf("last working build" to "build", "last diagnosis" to "diagnosis", "last accepted decision" to "decision", "last completed pr" to "pr")
    fun query(input: String): Pair<String, String>? = aliases[input.trim().lowercase()]?.let { it to "" }
        ?: search.matchEntire(input.trim())?.let { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    data class Annotation(val outcome: String, val receipt: String, val description: String)
    fun annotation(input: String): Annotation? = note.matchEntire(input.trim())?.let {
        Annotation(it.groupValues[1].lowercase().ifEmpty { "visual" }, it.groupValues[2].lowercase(), it.groupValues[3].trim())
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun messageId(record: JsonObject, chatId: Int): Int? {
        if (record.text("kind") == "decision") return record.text("source_message_id").toIntOrNull()?.takeIf { it > 0 }
        if (record.text("kind") != "user_outcome" || record.text("chat_id") != chatId.toString()) return null
        val task = record.text("task_id").split(':')
        return task.takeIf { it.size == 2 && it.first() == chatId.toString() }?.last()?.toIntOrNull()?.takeIf { it > 0 }
    }

    fun category(record: JsonObject): String? {
        if (record.text("kind") == "user_outcome" &&
            record.text("confidence") == "USER_REPORTED" &&
            record.text("source_receipt").matches(Regex("ev_[a-f0-9]{24}"))
        ) {
            return record.text("outcome").takeIf { it in setOf("worked", "failed", "visual") }
        }
        if (record.text("kind") == "decision" && record.text("confidence") == "USER_DECISION_NOT_EXECUTION_AUTHORITY") return "decision"
        if (record.text("schema") != "github.evidence.v1" ||
            record["trusted"] != JsonPrimitive(true) ||
            record["success"] != JsonPrimitive(true) ||
            record["verification_eligible"] == JsonPrimitive(false) ||
            record.text("repository_role") == "READ_SOURCE"
        ) {
            return null
        }
        val tool = record.text("tool").githubOperationFamily()
        val args = record["arguments"] as? JsonObject ?: return null
        val result = GitHubInventoryObservation.planningRoot(record) ?: return null
        return when (tool) {
            "github_ci" -> when {
                args.text("action") == "failure" && (result["diagnosis"] as? JsonArray)?.isNotEmpty() == true -> "diagnosis"
                listOf(result).plus((result["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()).any {
                    it.text("conclusion") == "success" &&
                        it.text("status") == "completed" &&
                        it.text("head_sha").matches(revision) &&
                        ((it["id"] ?: it["run_id"]) as? JsonPrimitive)?.longOrNull?.let { id -> id > 0 } == true &&
                        Regex("""(?i)\b(?:build|assemble|package|compile)\b""").containsMatchIn(it.text("name") + " " + it.text("workflow_name"))
                } -> "build"
                else -> null
            }
            "github_pr" -> {
                val pr = (result["pull_request"] as? JsonObject) ?: result
                "pr".takeIf {
                    (pr["number"] as? JsonPrimitive)?.longOrNull?.let { id -> id > 0 } == true &&
                        (pr["merged"] == JsonPrimitive(true) || pr.text("merged_at").isNotBlank())
                }
            }
            else -> null
        }
    }
}
