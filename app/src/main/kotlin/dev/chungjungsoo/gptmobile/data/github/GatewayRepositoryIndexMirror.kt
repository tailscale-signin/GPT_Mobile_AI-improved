package dev.chungjungsoo.gptmobile.data.github

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Verified shards are written first; only a complete manifest replaces the last valid map. */
internal class GatewayRepositoryIndexMirror(private val store: GitHubRepositoryStateStore, private val scope: String) {
    private val currentKey = "phone-graph:gateway-sync:current"
    private val pendingKey = "phone-graph:gateway-sync:pending"

    suspend fun request(commitSha: String): JsonObject {
        val current = store.read(scope, currentKey)?.takeIf { retained(it) }
        var pending = store.read(scope, pendingKey)?.takeIf { it.text("commit_sha") == commitSha }
        if (current == null && pending?.get("delta") == JsonPrimitive(true)) {
            restartTransfer()
            pending = null
        }
        return buildJsonObject {
            put("action", "sync")
            current?.let {
                put("base_sha", it.getValue("commit_sha"))
                put("base_generation", it.getValue("generation"))
            }
            pending?.let {
                put("sync_generation", it.getValue("generation"))
                put("transfer_id", it.getValue("transfer_id"))
                put("sync_offset", it.getValue("next_offset"))
            }
        }
    }

    suspend fun accept(page: JsonObject, commitSha: String, canApply: suspend () -> Boolean = { true }): Boolean {
        require(canApply()) { "Repository synchronization permission changed." }
        if (page.text("status") == "NOT_INDEXED") return false
        val metadata = requireNotNull(page["graph"] as? JsonObject)
        require(metadata.text("commit_sha") == commitSha) { "Repository index revision changed during transfer." }
        val generation = page.text("generation").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val access = page.text("access_scope").also { require(it.matches(Regex("(?:public:)?[a-f0-9]{32}"))) }
        val offset = requireNotNull((page["offset"] as? JsonPrimitive)?.intOrNull).also { require(it >= 0) }
        val pending = store.read(scope, pendingKey)
        val current = store.read(scope, currentKey)
        val initial = if (offset == 0) {
            if (page["delta"] == JsonPrimitive(true)) {
                require(current != null && current["generation"] == page["base_generation"] && current.text("access_scope") == access) { "Repository index delta has no verified base." }
                current
            } else {
                null
            }
        } else {
            require(
                pending != null &&
                    pending["generation"] == page["generation"] &&
                    pending["transfer_id"] == page["transfer_id"] &&
                    pending["next_offset"] == page["offset"] &&
                    pending.text("commit_sha") == commitSha &&
                    pending.text("access_scope") == access
            ) { "Repository index page does not continue the retained transfer." }
            pending
        }
        val entries = (initial?.get("entries") as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        val records = requireNotNull(page["records"] as? JsonArray).also { require(it.size <= 100) }
        records.forEach { value ->
            require(canApply()) { "Repository synchronization permission changed." }
            val row = value.jsonObject
            val path = row.text("path")
            require(path.isNotBlank() && path.length <= 4096 && path.split('/').none { it.isBlank() || it in setOf(".", "..") } && path.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' }) { "Repository index path is invalid." }
            if (row["deleted"] == JsonPrimitive(true)) {
                require(page["delta"] == JsonPrimitive(true))
                entries.remove(path)
            } else {
                require(row.text("encoding") == "zlib-base64")
                val expected = row.text("digest").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val sha = row.text("blob_sha").also { require(it.matches(Regex("[a-f0-9]{40}"))) }
                val encoded = row.text("payload").also { require(it.length <= 48_000) }
                val output = ByteArrayOutputStream()
                InflaterInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 32_768) { "Repository index record exceeds its decoded bound." }
                        output.write(buffer, 0, count)
                    }
                }
                val payload = output.toByteArray().decodeToString(throwOnInvalidSequence = true)
                require(GitHubRepositoryStateStore.digest(payload) == expected) { "Repository index record failed content verification." }
                val record = Json.parseToJsonElement(payload).jsonObject
                require(record.text("path") == path && record.text("blob_sha") == sha && record["source_level_only"] == JsonPrimitive(true) && record["runtime_verified"] == JsonPrimitive(false))
                store.write(scope, "phone-graph:gateway-sync:record:$expected", record)
                entries[path] = buildJsonObject {
                    put("blob_sha", sha)
                    put("digest", expected)
                }
            }
        }
        require(entries.size <= 25_000) { "Repository index mirror reached its bounded phone capacity." }
        val next = (page["next_offset"] as? JsonPrimitive)?.intOrNull
        require(page["next_offset"] == JsonNull || next != null)
        if (next != null) require(next == offset + records.size && next > offset)
        val completed = next == null
        if (completed) require((page["change_count"] as? JsonPrimitive)?.intOrNull == offset + records.size)
        val saved = buildJsonObject {
            put("commit_sha", commitSha)
            put("generation", generation)
            put("access_scope", access)
            put("transfer_id", page.getValue("transfer_id"))
            put("delta", page.getValue("delta"))
            put("next_offset", next?.let(::JsonPrimitive) ?: JsonNull)
            put("entries", JsonObject(entries))
            put("graph", metadata)
            put("observed_branch_head", page.getValue("observed_branch_head"))
            put("synchronized_at", System.currentTimeMillis())
            put(
                "state",
                if (!completed) {
                    "UPDATING"
                } else if (page["stale"] == JsonPrimitive(true)) {
                    "OFFLINE_SNAPSHOT"
                } else if (metadata["coverage_complete"] == JsonPrimitive(true)) {
                    "UP_TO_DATE"
                } else {
                    "PARTIAL_INDEX"
                }
            )
        }
        if (completed) {
            require((page["record_count"] as? JsonPrimitive)?.intOrNull == entries.size)
            val identity = entries.toSortedMap().entries.joinToString("") { (path, value) ->
                val row = value.jsonObject
                "$path\u0000${row.text("blob_sha")}\u0000${row.text("digest")}\n"
            }
            require(GitHubRepositoryStateStore.digest(identity) == generation) { "Repository index manifest failed content verification." }
            val keys = entries.values.map { "phone-graph:gateway-sync:record:${it.jsonObject.text("digest")}" }.toSet()
            require(store.presentKeys(scope, keys, requireRetained = true).size == keys.size) { "Repository index shards were evicted before transfer completion." }
            require(canApply()) { "Repository synchronization permission changed." }
            store.write(scope, currentKey, saved)
            store.remove(scope, pendingKey)
        } else {
            require(canApply()) { "Repository synchronization permission changed." }
            store.write(scope, pendingKey, saved)
        }
        return completed
    }

    suspend fun restartTransfer() = store.remove(scope, pendingKey)

    suspend fun snapshot(): JsonObject? = store.read(scope, currentKey)?.let {
        if (retained(it)) it else JsonObject(it + mapOf("state" to JsonPrimitive("PARTIAL_INDEX"), "source_cache_evicted" to JsonPrimitive(true)))
    }

    private suspend fun retained(snapshot: JsonObject): Boolean {
        val entries = snapshot["entries"] as? JsonObject ?: return false
        val keys = entries.values.map { "phone-graph:gateway-sync:record:${it.jsonObject.text("digest")}" }.toSet()
        return store.presentKeys(scope, keys, requireRetained = true).size == keys.size
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
