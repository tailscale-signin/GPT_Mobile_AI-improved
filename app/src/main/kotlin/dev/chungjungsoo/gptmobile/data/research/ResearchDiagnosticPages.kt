package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticValueProjector
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticJsonShapeFailure
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class ResearchDiagnosticPageFailure(val reason: String) : IOException(reason)

/** The size check applies to bytes actually read, including growth after a metadata check. */
internal fun readResearchDiagnosticPage(input: InputStream, maximumBytes: Int): String {
    require(maximumBytes in 1..512 * 1024)
    val buffer = ByteArray(maximumBytes + 1)
    var used = 0
    while (used < buffer.size) {
        val count = input.read(buffer, used, buffer.size - used)
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            buffer[used++] = byte.toByte()
        } else {
            used += count
        }
    }
    if (used == 0) throw ResearchDiagnosticPageFailure("RESEARCH_PAGE_EMPTY")
    if (used > maximumBytes) throw ResearchDiagnosticPageFailure("RESEARCH_PAGE_BYTE_LIMIT")
    return buffer.decodeToString(0, used, throwOnInvalidSequence = true)
}

/** Traverses only the typed reference graph rooted in an independently validated chat session. */
internal class ResearchDiagnosticPages(
    private val directory: File,
    private val maximumBytes: Int,
    private val beforeRead: suspend () -> Unit,
    private val read: (File, Int) -> String,
    private val consume: suspend (JsonObject) -> Unit,
    private val omitted: (String) -> Unit
) {
    private enum class Role { SESSION, SOURCE_INDEX, CLAIM_INDEX, QUERY_INDEX, SOURCE, CLAIM, CHUNK }
    private val referencePattern = Regex("[a-f0-9]{64}\\.(index|source|claim|chunk)")
    private val visited = hashSetOf<String>()
    private var readBytes = 0L

    suspend fun session(file: File, chatId: Int, runId: String, authority: String) {
        require(chatId > 0)
        visited.clear()
        readBytes = 0
        page(file, chatId, runId, authority, Role.SESSION, 0)
    }

    private suspend fun page(file: File, chatId: Int, runId: String, authority: String, role: Role, depth: Int) {
        // Budget/cancellation failures intentionally stay outside all source-error catch blocks.
        beforeRead()
        if (depth > 8 || visited.size >= 2048 || readBytes >= 64L * 1024 * 1024) {
            omitted("RESEARCH_TRAVERSAL_LIMIT")
            return
        }
        if (!visited.add(file.name)) return
        val limit = minOf(
            maximumBytes,
            when (role) {
                Role.SESSION -> 512 * 1024
                Role.CLAIM -> 16 * 1024
                Role.CHUNK -> 128 * 1024
                else -> 64 * 1024
            }
        )
        val raw = try {
            if (file.canonicalFile.parentFile != directory.canonicalFile) throw ResearchDiagnosticPageFailure("RESEARCH_REFERENCE_SCOPE_MISMATCH")
            read(file, limit)
        } catch (failure: ResearchDiagnosticPageFailure) {
            omitted(failure.reason)
            return
        } catch (_: IOException) {
            omitted("RESEARCH_PAGE_READ_FAILED")
            return
        } catch (_: SecurityException) {
            omitted("RESEARCH_PAGE_READ_DENIED")
            return
        }
        val encoded = raw.toByteArray(Charsets.UTF_8)
        readBytes += encoded.size
        if (encoded.size > limit) {
            omitted("RESEARCH_PAGE_BYTE_LIMIT")
            return
        }
        if (role != Role.SESSION && hash(encoded) + "." + file.extension != file.name) {
            omitted("RESEARCH_PAGE_DIGEST_MISMATCH")
            return
        }
        val value = if (role == Role.CHUNK) {
            JsonPrimitive(raw)
        } else {
            val shapeFailure = diagnosticJsonShapeFailure(raw)
            if (shapeFailure != null) {
                omitted(shapeFailure)
                return
            }
            try {
                Json.parseToJsonElement(raw)
            } catch (_: IllegalArgumentException) {
                omitted("RESEARCH_PAGE_INVALID_JSON")
                return
            }
        }
        if (role == Role.SESSION) {
            val root = value as? JsonObject
            if (root == null ||
                (root["chatId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() != chatId ||
                (root["runId"] as? JsonPrimitive)?.contentOrNull != runId ||
                ((root["authority"] as? JsonPrimitive)?.contentOrNull ?: "legacy") != authority ||
                file.name != hash(runId.toByteArray(Charsets.UTF_8)) + ".json"
            ) {
                omitted("RESEARCH_SESSION_SCOPE_MISMATCH")
                return
            }
        }
        if (role in setOf(Role.SOURCE, Role.CLAIM) && value !is JsonObject) {
            omitted("RESEARCH_PAGE_INVALID_SHAPE")
            return
        }
        if (role in setOf(Role.SOURCE_INDEX, Role.CLAIM_INDEX, Role.QUERY_INDEX) && (value !is JsonArray || value.size > 100)) {
            omitted("RESEARCH_INDEX_INVALID")
            return
        }
        val projection = DiagnosticValueProjector(maximumBytes, omitted).element(value)
        consume(
            buildJsonObject {
                put("kind", if (role == Role.SESSION) "session" else file.extension)
                put("runId", runId)
                put("sourceReference", file.name)
                put("payload", projection)
            }
        )
        when (role) {
            Role.SESSION -> {
                val root = value as JsonObject
                for ((key, childRole) in listOf(
                    "sourcePages" to Role.SOURCE_INDEX,
                    "sourceRefs" to Role.SOURCE,
                    "claimPages" to Role.CLAIM_INDEX,
                    "queryPages" to Role.QUERY_INDEX
                )) {
                    for (reference in (root[key] as? JsonArray).orEmpty()) {
                        follow(reference, childRole, chatId, runId, authority, depth)
                    }
                }
            }
            Role.SOURCE_INDEX, Role.CLAIM_INDEX -> {
                val leaf = if (role == Role.SOURCE_INDEX) Role.SOURCE else Role.CLAIM
                for (reference in value as JsonArray) {
                    val name = (reference as? JsonPrimitive)?.contentOrNull.orEmpty()
                    follow(reference, if (name.endsWith(".index")) role else leaf, chatId, runId, authority, depth)
                }
            }
            Role.QUERY_INDEX -> {
                // Legacy query leaves and index references share one untyped string format.
                // Treat a query as text; never open another page based on user query text.
                if ((value as JsonArray).any { (it as? JsonPrimitive)?.contentOrNull?.matches(Regex("[a-f0-9]{64}\\.index")) == true }) {
                    omitted("QUERY_INDEX_DESCENDANTS_UNVERIFIED")
                }
            }
            Role.SOURCE -> {
                for (reference in ((value as? JsonObject)?.get("bodyChunks") as? JsonArray).orEmpty()) {
                    follow(reference, Role.CHUNK, chatId, runId, authority, depth)
                }
            }
            else -> Unit
        }
    }

    private suspend fun follow(value: JsonElement, role: Role, chatId: Int, runId: String, authority: String, depth: Int) {
        val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val extension = when (role) {
            Role.SOURCE_INDEX, Role.CLAIM_INDEX, Role.QUERY_INDEX -> "index"
            Role.SOURCE -> "source"
            Role.CLAIM -> "claim"
            Role.CHUNK -> "chunk"
            Role.SESSION -> "json"
        }
        if (!referencePattern.matches(name) || !name.endsWith(".$extension")) {
            omitted("RESEARCH_REFERENCE_INVALID")
            return
        }
        page(File(directory, name), chatId, runId, authority, role, depth + 1)
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
