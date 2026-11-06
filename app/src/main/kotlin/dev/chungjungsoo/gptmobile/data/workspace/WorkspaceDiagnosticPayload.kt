package dev.chungjungsoo.gptmobile.data.workspace

import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticValueProjector
import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticJsonShapeFailure
import dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class WorkspaceDiagnosticPayload(
    val protected: Boolean,
    val payload: JsonElement? = null,
    val omissionReason: String? = null
)

/** Reads at most one bounded receipt, validating authority both before and after decryption. */
internal suspend fun readWorkspaceDiagnosticPayload(
    chatId: Int,
    metadata: JsonObject,
    maximumBytes: Int,
    beforeRead: suspend () -> Unit,
    authorized: suspend () -> Boolean,
    storedSize: suspend (String) -> Long?,
    read: suspend (String) -> ByteArray?,
    omitted: (String) -> Unit
): WorkspaceDiagnosticPayload {
    require(maximumBytes in 1024..1_048_576)
    val chunks = metadata["protectedChunks"]
    val single = metadata["protectedReference"]
    if (chunks == null && (single == null || single.toString() == "null")) return WorkspaceDiagnosticPayload(false)
    fun missing(reason: String) = WorkspaceDiagnosticPayload(true, omissionReason = reason)
    beforeRead()
    if (chatId <= 0 || !authorized()) return missing("PROTECTED_SOURCE_UNAVAILABLE")
    val references = when {
        chunks is JsonArray && chunks.size in 1..25 -> chunks.map { (it as? JsonPrimitive)?.contentOrNull }
        chunks != null -> return missing("PROTECTED_REFERENCE_INVALID")
        single is JsonPrimitive && single.isString -> listOf(single.content)
        else -> return missing("PROTECTED_REFERENCE_INVALID")
    }
    val scope = Regex("workspace-memory-$chatId-[A-Za-z0-9_-]{1,90}")
    if (references.any { it == null || !scope.matches(it) } || references.distinct().size != references.size) {
        return missing("PROTECTED_REFERENCE_SCOPE_MISMATCH")
    }
    val expectedDigest = if (chunks != null) (metadata["digest"] as? JsonPrimitive)?.contentOrNull else null
    if (chunks != null && (expectedDigest == null || !expectedDigest.matches(Regex("[a-f0-9]{64}")))) {
        return missing("PROTECTED_DIGEST_UNAVAILABLE")
    }
    var physicalBytes = 0L
    for (reference in references) {
        beforeRead()
        val size = try {
            storedSize(requireNotNull(reference))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return missing("PROTECTED_STORAGE_UNAVAILABLE")
        } ?: return missing("PROTECTED_SIZE_UNAVAILABLE")
        // AndroidSecretVault stores at most 64 KiB plus its authenticated envelope.
        if (size !in 1..(64 * 1024L + 64)) return missing("PROTECTED_CHUNK_SIZE_LIMIT")
        physicalBytes += size
        if (physicalBytes > maximumBytes.toLong() + references.size * 64L) return missing("PROTECTED_PAYLOAD_BYTE_LIMIT")
    }
    val output = ByteArray(maximumBytes)
    var used = 0
    try {
        for (reference in references) {
            beforeRead()
            val chunk = try {
                read(requireNotNull(reference))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return missing("PROTECTED_STORAGE_UNAVAILABLE")
            } ?: return missing("PROTECTED_CHUNK_MISSING")
            try {
                if (chunk.size > 64 * 1024 || chunk.size > output.size - used) return missing("PROTECTED_PAYLOAD_BYTE_LIMIT")
                chunk.copyInto(output, used)
                used += chunk.size
            } finally {
                chunk.fill(0)
            }
        }
        if (expectedDigest != null) {
            val digest = MessageDigest.getInstance("SHA-256").apply { update(output, 0, used) }
                .digest().joinToString("") { "%02x".format(it) }
            if (digest != expectedDigest) return missing("PROTECTED_DIGEST_MISMATCH")
        }
        val text = try {
            output.decodeToString(0, used, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return missing("PROTECTED_PAYLOAD_INVALID_UTF8")
        }
        val shapeFailure = diagnosticJsonShapeFailure(text)
        if (shapeFailure != null) return missing(shapeFailure)
        val value = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return missing("PROTECTED_PAYLOAD_INVALID_JSON")
        beforeRead()
        if (!authorized()) return missing("PROTECTED_SOURCE_CHANGED")
        val projected = DiagnosticValueProjector(maximumBytes, omitted).element(value)
        return WorkspaceDiagnosticPayload(true, redactChatDebugData(projected))
    } finally {
        output.fill(0)
    }
}
