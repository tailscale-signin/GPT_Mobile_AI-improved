package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A retained handle is immutable. Selecting another revision requires an explicit new read. */
internal object GitHubEvidenceRevisionGuard {
    fun validate(owner: String, repo: String, arguments: JsonObject, retained: JsonObject) {
        val requested = arguments.text("commit_sha")
        val actual = retained.text("commit_sha")
        if (requested.isBlank() || requested.equals(actual, true)) return
        val historical = buildJsonObject {
            put("action", "ranges")
            put("owner", owner)
            put("repo", repo)
            put("commit_sha", actual)
            put("evidence_id", arguments.getValue("evidence_id"))
        }
        // Only caller-selected ranges can establish the intended current read.
        // Historical ranges are not silently promoted to a different revision.
        // The public read contract uses commit_sha; a native selected ref is not
        // a model-facing selector and cannot replace this explicit immutable pin.
        val ranges = arguments["ranges"] as? JsonArray
        val current = if (requested.matches(Regex("[a-fA-F0-9]{40}")) && !ranges.isNullOrEmpty()) {
            JsonObject(
                arguments.filterKeys { it in setOf("owner", "repo", "commit_sha", "ranges") } + mapOf(
                    "action" to JsonPrimitive("ranges"),
                    "owner" to JsonPrimitive(owner),
                    "repo" to JsonPrimitive(repo)
                )
            )
        } else {
            null
        }
        val currentRead = current?.takeIf {
            ToolArgumentValidator.errors(it, GitHubOperationTool.definition("github_read").inputSchema).isEmpty() &&
                GitHubOperationArguments.errors("github_read", it).isEmpty()
        }
        throw GitHubReadValidation(
            "EVIDENCE_REVISION_MISMATCH",
            buildJsonObject {
                put("schema", "github.read_validation.v1")
                put("status", "PRECHECK_REJECTED")
                put("error_code", "EVIDENCE_REVISION_MISMATCH")
                put("reason_code", "EVIDENCE_REVISION_MISMATCH")
                put("failed_stage", "retained_evidence_identity")
                put("read_dispatched", false)
                put("retry_unchanged", false)
                put("automatic_repair_allowed", false)
                put("owner", owner)
                put("repo", repo)
                put("requested_commit_sha", requested)
                put("evidence_commit_sha", actual)
                put("evidence_id", arguments.getValue("evidence_id"))
                put("message", "The retained source belongs to a different commit. Choose its historical revision, or read the explicitly requested ranges at the requested revision. The selected repository revision and saved evidence are unchanged.")
                put("recovery_status", "CHOICE_REQUIRED")
                put(
                    "corrective_actions",
                    JsonArray(
                        listOfNotNull(
                            buildJsonObject {
                                put("intent", "REOPEN_RETAINED_REVISION")
                                put("tool", "github_read")
                                put("arguments", historical)
                            },
                            currentRead?.let { corrected ->
                                buildJsonObject {
                                    put("intent", "READ_REQUESTED_REVISION")
                                    put("tool", "github_read")
                                    put("arguments", corrected)
                                    put("source_existence_verified", false)
                                }
                            }
                        )
                    )
                )
                if (currentRead == null) put("next_action", "Select exact paths and ranges at requested_commit_sha for a new read; do not reuse this evidence_id for that revision.")
            }
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
}
