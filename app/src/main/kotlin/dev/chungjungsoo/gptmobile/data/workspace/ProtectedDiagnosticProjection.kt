package dev.chungjungsoo.gptmobile.data.workspace

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticJsonShapeScanner
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticValueProjector
import dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticNodeBudget
import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import java.io.FilterReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A diagnostic-only summary of an oversized protected checkpoint. The original is
 * still omitted, never silently promoted to a complete source or used for resume.
 * Only one vault chunk and an 8 KiB pipe are live alongside the bounded summary.
 */
internal suspend fun readProtectedDiagnosticProjection(
    references: List<String>,
    expectedDigest: String,
    maximumBytes: Int,
    beforeRead: suspend () -> Unit,
    authorized: suspend () -> Boolean,
    read: suspend (String) -> ByteArray?,
    omitted: (String) -> Unit,
    projector: DiagnosticValueProjector?
): WorkspaceDiagnosticPayload = coroutineScope {
    val sourceLimit = 1_500_000 // The unchanged protected-checkpoint retention bound.
    val input = ClearingDiagnosticPipe()
    val output = PipedOutputStream(input)
    val context = currentCoroutineContext()
    val parsed = async(Dispatchers.IO) {
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val reader = object : FilterReader(InputStreamReader(input, decoder)) {
                private val shape = DiagnosticJsonShapeScanner(diagnosticNodeBudget(maximumBytes), 24)
                private var quoted = false
                private var escaped = false
                private var stringCharacters = 0

                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    context.ensureActive()
                    val count = super.read(buffer, offset, length)
                    for (index in offset until offset + count.coerceAtLeast(0)) {
                        val character = buffer[index]
                        shape.accept(character)?.let { throw IOException(it) }
                        if (quoted) {
                            // Bound even one scalar token before Gson can materialize it.
                            if (++stringCharacters > 64 * 1024) throw ProtectedProjectionLimit()
                            if (escaped) {
                                escaped = false
                            } else if (character == '\\') {
                                escaped = true
                            } else if (character == '"') {
                                quoted = false
                            }
                        } else if (character == '"') {
                            quoted = true
                            stringCharacters = 0
                        }
                    }
                    return count
                }
            }
            val value = JsonReader(reader).use { json ->
                json.strictness = Strictness.STRICT
                ProtectedProjectionReader(maximumBytes).read(json)
            }
            ProtectedProjectionRead(value = value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProtectedProjectionLimit) {
            ProtectedProjectionRead(omissionReason = "PROTECTED_PAYLOAD_BYTE_LIMIT")
        } catch (_: Exception) {
            ProtectedProjectionRead(omissionReason = "PROTECTED_PAYLOAD_INVALID_JSON")
        } finally {
            input.close()
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var used = 0
    var sourceFailure: String? = null
    try {
        withContext(Dispatchers.IO) {
            output.use { stream ->
                for (reference in references) {
                    beforeRead()
                    val chunk = try {
                        read(reference)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (deferred: JavaHeapWorkDeferred) {
                        throw deferred
                    } catch (_: Exception) {
                        sourceFailure = "PROTECTED_STORAGE_UNAVAILABLE"
                        break
                    }
                    if (chunk == null) {
                        sourceFailure = "PROTECTED_CHUNK_MISSING"
                        break
                    }
                    try {
                        if (chunk.size > 64 * 1024 || used + chunk.size > sourceLimit) {
                            sourceFailure = "PROTECTED_PAYLOAD_BYTE_LIMIT"
                            break
                        }
                        used += chunk.size
                        digest.update(chunk)
                        try {
                            stream.write(chunk)
                        } catch (_: IOException) {
                            // A strict parser can close the pipe before its producer ends.
                            sourceFailure = "PROTECTED_PAYLOAD_INVALID_JSON"
                            break
                        }
                    } finally {
                        chunk.fill(0)
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } finally {
        output.close()
    }
    val projection = parsed.await()
    if (sourceFailure != null && sourceFailure != "PROTECTED_PAYLOAD_INVALID_JSON") {
        return@coroutineScope WorkspaceDiagnosticPayload(true, omissionReason = sourceFailure)
    }
    // A bounded reader can close the pipe without the source being malformed.
    // Preserve its typed limit so compact matching receipts can still explain
    // the omitted checkpoint; never publish a partial unauthenticated payload.
    projection.omissionReason?.let { return@coroutineScope WorkspaceDiagnosticPayload(true, omissionReason = it) }
    if (sourceFailure != null) return@coroutineScope WorkspaceDiagnosticPayload(true, omissionReason = sourceFailure)
    if (DiagnosticRecordChunks.hex(digest.digest()) != expectedDigest) {
        return@coroutineScope WorkspaceDiagnosticPayload(true, omissionReason = "PROTECTED_DIGEST_MISMATCH")
    }
    val value = requireNotNull(projection.value)
    beforeRead()
    if (!authorized()) return@coroutineScope WorkspaceDiagnosticPayload(true, omissionReason = "PROTECTED_SOURCE_CHANGED")
    val summary = JsonObject(
        value + (
            "diagnostic_partial_payload" to buildJsonObject {
                put("representation", "BOUNDED_PROTECTED_CHECKPOINT_SUMMARY")
                put("source_sha256", expectedDigest)
                put("source_bytes", used)
                put("complete", false)
                put("reason", "PROTECTED_PAYLOAD_BYTE_LIMIT")
                put("scope", "Diagnostic summary only; omitted checkpoint content is not reconstructed.")
            }
            )
    )
    val safe = redactChatDebugData((projector ?: DiagnosticValueProjector(maximumBytes, omitted)).element(summary))
    beforeRead()
    if (!authorized()) return@coroutineScope WorkspaceDiagnosticPayload(true, omissionReason = "PROTECTED_SOURCE_CHANGED")
    // Keep the original omission visible even though useful bounded evidence survived.
    omitted("PROTECTED_PAYLOAD_BYTE_LIMIT")
    return@coroutineScope WorkspaceDiagnosticPayload(true, payload = safe, decodedBytes = used)
}

private class ProtectedProjectionLimit : IOException()

private data class ProtectedProjectionRead(val value: JsonObject? = null, val omissionReason: String? = null)

private class ClearingDiagnosticPipe : PipedInputStream(8192) {
    override fun close() {
        super.close()
        buffer.fill(0)
    }
}

/** Select only known summary fields; large arrays and source text are skipped by the parser. */
private class ProtectedProjectionReader(maximumBytes: Int) {
    private var remaining = minOf(maximumBytes / 2, 64 * 1024)
    private var nodes = 4096
    private val scalarFields = setOf(
        "schema", "run_id", "binding", "ownership_fence", "task_state", "task_outcome", "stop_code", "verification",
        "latest_verdict", "completion_verified", "response_delivered", "required_criteria", "verified_criteria",
        "failure_code", "failed_stage", "recovery_action", "invocation_id", "dispatched", "retryable", "tested_sha",
        "observed_head_sha", "verdict_sha256", "criterion_count", "included_count", "omitted_count", "complete"
    )
    private val summaryFields = setOf("acceptance_explanation", "criterion_state_counts", "blocker_counts", "evidence_class_counts", "diagnostic_projection")

    fun read(reader: JsonReader): JsonObject {
        require(reader.peek() == JsonToken.BEGIN_OBJECT)
        val fields = linkedMapOf<String, JsonElement>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(name.length <= 256)
            when {
                name in scalarFields && reader.peek() !in setOf(JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT) -> boundedValue(reader, 0)?.let { fields[name] = it }
                name in summaryFields -> boundedValue(reader, 0)?.let { fields[name] = it }
                name == "acceptance_ledger" && reader.peek() == JsonToken.BEGIN_OBJECT -> fields[name] = ledgerSummary(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return JsonObject(fields)
    }

    private fun ledgerSummary(reader: JsonReader): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(name.length <= 256)
            if (name in scalarFields || name in summaryFields || name == "diagnostic_projection") {
                boundedValue(reader, 0)?.let { fields[name] = it }
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return JsonObject(fields)
    }

    private fun boundedValue(reader: JsonReader, depth: Int): JsonElement? {
        if (remaining <= 0 || nodes <= 0 || depth > 16) {
            reader.skipValue()
            return null
        }
        nodes--
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val fields = linkedMapOf<String, JsonElement>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name.length > 256 || remaining < name.length) {
                        reader.skipValue()
                    } else {
                        remaining -= name.length
                        boundedValue(reader, depth + 1)?.let { fields[name] = it }
                    }
                }
                reader.endObject()
                JsonObject(fields)
            }
            JsonToken.BEGIN_ARRAY -> {
                val values = mutableListOf<JsonElement>()
                reader.beginArray()
                while (reader.hasNext()) boundedValue(reader, depth + 1)?.let(values::add)
                reader.endArray()
                JsonArray(values)
            }
            JsonToken.STRING -> reader.nextString().let { value ->
                // Retained scalar strings are independently bounded; the source itself
                // remains below the existing 1.5 MB protected-retention ceiling.
                if (value.length > minOf(remaining, 4096)) {
                    null
                } else {
                    remaining -= value.length
                    JsonPrimitive(value)
                }
            }
            JsonToken.NUMBER -> reader.nextString().let { value ->
                if (value.length > 128 || value.length > remaining) null else Json.parseToJsonElement(value).also { remaining -= value.length }
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull
            }
            else -> error("Invalid diagnostic JSON")
        }
    }
}
