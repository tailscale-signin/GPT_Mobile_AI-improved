package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Private content-addressed chunks avoid copying earlier recovery narratives into new evidence. */
internal class GitHubReviewCheckpoint(
    private val task: String,
    private val read: suspend (String) -> JsonObject?,
    private val write: suspend (String, JsonObject) -> Unit
) {
    data class Saved(val draft: String, val receipts: List<String>, val blockedReason: String? = null, val inputLimit: Int? = null, val reviewerRevision: String = "", val reviewed: Boolean = false)
    private val taskHash = researchHash(task)
    private suspend fun text(value: String): String {
        require(value.toByteArray().size <= 16 * 1024 * 1024)
        val digest = researchHash(value)
        if (read("text:$digest") == null) {
            val chunks = mutableListOf<String>()
            var at = 0
            while (at < value.length) {
                var end = minOf(value.length, at + 32_000)
                if (end < value.length && value[end].isLowSurrogate()) end--
                chunks += value.substring(at, end)
                at = end
            }
            chunks.forEachIndexed { index, part -> write("chunk:$digest:$index", buildJsonObject { put("text", part) }) }
            write(
                "text:$digest",
                buildJsonObject {
                    put("count", chunks.size)
                    put("digest", digest)
                }
            )
        }
        return digest
    }
    private suspend fun restore(digest: String): String {
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val count = requireNotNull(read("text:$digest"))["count"]!!.jsonPrimitive.content.toInt().also { require(it in 0..1024) }
        return buildString {
            repeat(count) { index ->
                val part = requireNotNull(read("chunk:$digest:$index"))["text"]!!.jsonPrimitive.content
                require(part.length <= 32_000 && length.toLong() + part.length <= 16 * 1024 * 1024)
                append(part)
            }
        }.also { require(it.toByteArray().size <= 16 * 1024 * 1024 && researchHash(it) == digest) { "Review checkpoint content changed." } }
    }
    suspend fun save(draft: String, receipts: List<String>, inputLimit: Int? = null, reviewerRevision: String = "") {
        require(receipts.size <= 4096 && receipts.sumOf { it.toByteArray().size.toLong() } <= 16 * 1024 * 1024)
        val draftRef = text(draft)
        val refs = receipts.map { text(it) }
        val previous = read("active")
        val sameCandidate = previous?.get("task") == JsonPrimitive(taskHash) && previous["draft"] == JsonPrimitive(draftRef) && previous["receipts"] == JsonArray(refs.map(::JsonPrimitive)) && (previous["reviewer_revision"]?.jsonPrimitive?.content.orEmpty() == reviewerRevision)
        val sameBudget = previous?.get("task") == JsonPrimitive(taskHash) && previous["receipts"] == JsonArray(refs.map(::JsonPrimitive)) && previous["reviewer_revision"]?.jsonPrimitive?.content.orEmpty() == reviewerRevision
        write(
            "active",
            buildJsonObject {
                put("task", taskHash)
                put("draft", draftRef)
                put("receipts", JsonArray(refs.map(::JsonPrimitive)))
                put("state", "PENDING")
                put("generation_state", "COMPLETED")
                put("review_state", "PENDING")
                put("delivery_state", "AWAITING_REVIEW")
                put("verdict_schema_version", GitHubReviewSchema.VERSION)
                put("reviewer_revision", reviewerRevision)
                put("review_requirements", JsonArray((listOf("ORIGINAL_TASK", "NONEMPTY_DRAFT", "REVIEW_INPUT_CAPACITY") + if (GitHubRequestCompletion.requiresRepositoryEvidence(task)) listOf("REPOSITORY_EVIDENCE") else emptyList()).map(::JsonPrimitive)))
                inputLimit?.let { put("input_limit", it.coerceAtLeast(0)) }
                put("format_retries", if (sameCandidate) (previous.get("format_retries") as? JsonPrimitive)?.intOrNull ?: 0 else 0)
                put("review_passes", if (sameBudget) (previous.get("review_passes") as? JsonPrimitive)?.intOrNull ?: 0 else 0)
            }
        )
    }

    /** One explicit restart may recover a model format failure. Persist before dispatch. */
    suspend fun formatRetryAvailable(): Boolean {
        val current = read("active") ?: return false
        return current["task"] == JsonPrimitive(taskHash) &&
            current["state"] == JsonPrimitive("BLOCKED") &&
            current["failure_code"] == JsonPrimitive("GITHUB_REVIEW_INVALID_VERDICT") &&
            ((current["format_retries"] as? JsonPrimitive)?.intOrNull ?: 0) == 0
    }

    suspend fun claimFormatRetry(): Boolean {
        val current = read("active") ?: return false
        if (current["task"] != JsonPrimitive(taskHash) || current["state"] != JsonPrimitive("BLOCKED") || current["failure_code"] != JsonPrimitive("GITHUB_REVIEW_INVALID_VERDICT")) return false
        val attempts = (current["format_retries"] as? JsonPrimitive)?.intOrNull ?: 0
        if (attempts != 0) return false
        write("active", JsonObject(current + mapOf("state" to JsonPrimitive("PENDING"), "format_retries" to JsonPrimitive(1))))
        return true
    }
    suspend fun load(): Saved? {
        val row = read("active") ?: return null
        if (row["task"] != JsonPrimitive(taskHash) || row["state"] !in setOf(JsonPrimitive("PENDING"), JsonPrimitive("BLOCKED"), JsonPrimitive("REVIEWED"))) return null
        val refs = row["receipts"]!!.jsonArray.also { require(it.size <= 4096) }
        var bytes = 0L
        val receipts = refs.map {
            restore(it.jsonPrimitive.content).also { receipt ->
                bytes += receipt.toByteArray().size
                require(bytes <= 16 * 1024 * 1024)
            }
        }
        return Saved(restore(row["draft"]!!.jsonPrimitive.content), receipts, row["failure_code"]?.jsonPrimitive?.content.takeIf { row["state"] == JsonPrimitive("BLOCKED") }, (row["input_limit"] as? JsonPrimitive)?.intOrNull, row["reviewer_revision"]?.jsonPrimitive?.content.orEmpty(), row["state"] == JsonPrimitive("REVIEWED"))
    }

    /** Failure metadata is retained with the protected checkpoint even when verbose logging is disabled. */
    suspend fun failed(code: String, blocked: Boolean = true, details: JsonObject = JsonObject(emptyMap())) {
        val current = read("active") ?: return
        write(
            "active",
            JsonObject(
                current + details.filterKeys { it !in CHECKPOINT_FIELDS } + mapOf(
                    "state" to JsonPrimitive(if (blocked) "BLOCKED" else "PENDING"),
                    "failure_code" to JsonPrimitive(code),
                    "review_state" to JsonPrimitive(if (code == "GITHUB_REVIEW_INDETERMINATE") "INDETERMINATE" else "FAILED"),
                    "delivery_state" to JsonPrimitive("WITHHELD"),
                    "failed_stage" to (details["failed_stage"] ?: JsonPrimitive("answer_review")),
                    "review_stage" to JsonPrimitive("answer_review"),
                    "retryable_without_new_evidence" to JsonPrimitive(!blocked || code == "GITHUB_REVIEW_INVALID_VERDICT" && ((current["format_retries"] as? JsonPrimitive)?.intOrNull ?: 0) == 0)
                )
            )
        )
    }

    // Legacy prompt-only entries cannot establish which reviewer produced them.
    // Keep the saved draft, but require a new review when its configuration changes.
    private fun passKey(kind: String, prompt: String, reviewerRevision: String): String {
        val identity = buildJsonObject {
            put("task", taskHash)
            put("reviewer_revision", reviewerRevision)
            put("prompt", prompt)
        }
        return "$kind:v2:${researchHash(identity.toString())}"
    }

    suspend fun verdict(prompt: String, reviewerRevision: String): String? = read(passKey("verdict", prompt, reviewerRevision))?.get("text")?.jsonPrimitive?.content
    suspend fun verdict(prompt: String, result: String, reviewerRevision: String) {
        require(result.toByteArray().size <= 128 * 1024)
        write(passKey("verdict", prompt, reviewerRevision), buildJsonObject { put("text", result) })
    }
    suspend fun repair(prompt: String, reviewerRevision: String): String? {
        val row = read(passKey("repair", prompt, reviewerRevision)) ?: return null
        return restore(row["text"]!!.jsonPrimitive.content)
    }
    suspend fun repair(prompt: String, result: String, reviewerRevision: String) {
        require(result.isNotBlank() && result.length <= 1_048_576)
        val digest = text(result)
        write(passKey("repair", prompt, reviewerRevision), buildJsonObject { put("text", digest) })
    }
    suspend fun completed() {
        val current = read("active") ?: return
        write("active", JsonObject(current + mapOf("state" to JsonPrimitive("COMPLETED"), "delivery_state" to JsonPrimitive("VERIFIED_OR_EXECUTION_RESUMED"))))
    }

    /** A successful review is not delivery: deterministic claim verification still owns publication. */
    suspend fun reviewed() {
        val current = read("active") ?: return
        write("active", JsonObject(current + mapOf("state" to JsonPrimitive("REVIEWED"), "review_state" to JsonPrimitive("PASS"), "delivery_state" to JsonPrimitive("AWAITING_FACT_VERIFICATION"))))
    }

    /** Claim before dispatch so restarts cannot silently replenish a failed review budget. */
    suspend fun claimPass(maxPasses: Int): Boolean {
        val current = read("active") ?: return false
        if (current["task"] != JsonPrimitive(taskHash)) return false
        val used = (current["review_passes"] as? JsonPrimitive)?.intOrNull ?: 0
        if (used !in 0 until maxPasses) return false
        write("active", JsonObject(current + ("review_passes" to JsonPrimitive(used + 1))))
        return true
    }

    private companion object {
        // Failure diagnostics must never replace the durable state that owns recovery.
        val CHECKPOINT_FIELDS = setOf("task", "draft", "receipts", "reviewer_revision", "review_passes", "format_retries", "input_limit")
    }
}
