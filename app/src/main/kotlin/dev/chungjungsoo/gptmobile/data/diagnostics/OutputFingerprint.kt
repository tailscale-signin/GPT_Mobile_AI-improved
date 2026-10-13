package dev.chungjungsoo.gptmobile.data.diagnostics

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class OutputTraceBoundary { PROVIDER_VISIBLE, REDUCER, COMMITTED, RENDER_INPUT, RENDER_TRANSFORM }

@Serializable
enum class OutputTextRepresentation { VISIBLE_UTF8, DISPLAY_FILTERED_MARKDOWN_UTF8, MATH_PLACEHOLDER_MARKDOWN_UTF8 }

data class OutputTraceOwner(val runId: String, val fence: Long, val partId: String)

@Serializable
data class OutputFingerprint(val utf8Bytes: Long, val sha256: String)

/** Incremental visible text hashing without retaining response content or misencoding split surrogate pairs. */
internal class OutputFingerprintAccumulator {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var bytes = 0L
    private var pendingHighSurrogate: Char? = null

    fun append(text: String) {
        if (text.isEmpty()) return
        val combined = pendingHighSurrogate?.let { it + text } ?: text
        pendingHighSurrogate = combined.last().takeIf(Character::isHighSurrogate)
        val complete = if (pendingHighSurrogate != null) combined.dropLast(1) else combined
        val encoded = complete.toByteArray(Charsets.UTF_8)
        digest.update(encoded)
        bytes = Math.addExact(bytes, encoded.size.toLong())
    }

    // Some providers cannot clone MessageDigest. Capture becomes unavailable without affecting generation.
    fun snapshot(): OutputFingerprint? = runCatching {
        val copy = digest.clone() as MessageDigest
        val tail = pendingHighSurrogate?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        copy.update(tail)
        OutputFingerprint(Math.addExact(bytes, tail.size.toLong()), copy.digest().joinToString("") { "%02x".format(it) })
    }.getOrNull()
}

internal fun outputFingerprint(text: String): OutputFingerprint {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return OutputFingerprint(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
