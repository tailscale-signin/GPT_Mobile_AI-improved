package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Private content-addressed chunks avoid copying earlier recovery narratives into new evidence. */
internal class GitHubReviewCheckpoint(
    private val task: String,
    private val read: suspend (String) -> JsonObject?,
    private val write: suspend (String, JsonObject) -> Unit
) {
    data class Saved(val draft: String, val receipts: List<String>)
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
    suspend fun save(draft: String, receipts: List<String>) {
        require(receipts.size <= 4096 && receipts.sumOf { it.toByteArray().size.toLong() } <= 16 * 1024 * 1024)
        val draftRef = text(draft)
        val refs = receipts.map { text(it) }
        write(
            "active",
            buildJsonObject {
                put("task", taskHash)
                put("draft", draftRef)
                put("receipts", JsonArray(refs.map(::JsonPrimitive)))
                put("state", "PENDING")
            }
        )
    }
    suspend fun load(): Saved? {
        val row = read("active") ?: return null
        if (row["task"] != JsonPrimitive(taskHash) || row["state"] != JsonPrimitive("PENDING")) return null
        val refs = row["receipts"]!!.jsonArray.also { require(it.size <= 4096) }
        var bytes = 0L
        val receipts = refs.map {
            restore(it.jsonPrimitive.content).also { receipt ->
                bytes += receipt.toByteArray().size
                require(bytes <= 16 * 1024 * 1024)
            }
        }
        return Saved(restore(row["draft"]!!.jsonPrimitive.content), receipts)
    }
    suspend fun verdict(prompt: String): String? = read("verdict:${researchHash(prompt)}")?.get("text")?.jsonPrimitive?.content
    suspend fun verdict(prompt: String, result: String) {
        require(result.toByteArray().size <= 128 * 1024)
        write("verdict:${researchHash(prompt)}", buildJsonObject { put("text", result) })
    }
    suspend fun completed() {
        val current = read("active") ?: return
        write("active", JsonObject(current + ("state" to JsonPrimitive("COMPLETED"))))
    }
}
