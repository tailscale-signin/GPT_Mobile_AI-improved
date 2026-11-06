package dev.chungjungsoo.gptmobile.data.github

import java.nio.charset.CharacterCodingException
import java.util.Base64

/** Only a local content rejection may be downgraded to an unavailable catalog row. */
internal class GitHubBlobContentFailure(val code: String, message: String) : IllegalArgumentException(message)

internal object GitHubBlobContent {
    private const val MAX_BYTES = 2 * 1024 * 1024

    data class Text(val content: String, val bytes: Int)

    fun checkSize(size: Long) {
        if (size > MAX_BYTES) throw GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_LIMIT", "Blob exceeds the 2 MiB ingestion budget.")
    }

    fun decode(encoding: String, content: String): Text {
        val bytes = when (encoding) {
            "base64" -> {
                if (content.length > MAX_BYTES * 2) throw GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_LIMIT", "Encoded blob exceeds the ingestion budget.")
                try {
                    Base64.getMimeDecoder().decode(content)
                } catch (_: IllegalArgumentException) {
                    throw GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_INVALID", "Blob content is not valid base64.")
                }
            }
            "utf-8", "utf8", "" -> {
                checkSize(content.length.toLong())
                content.encodeToByteArray()
            }
            else -> throw GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_INVALID", "Blob content uses an unsupported encoding.")
        }
        checkSize(bytes.size.toLong())
        if (bytes.any { it == 0.toByte() }) throw GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_INVALID", "Only UTF-8 text blobs can be ingested.")
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw GitHubBlobContentFailure("GITHUB_BLOB_CONTENT_INVALID", "Blob content is not valid UTF-8.")
        }
        return Text(text, bytes.size)
    }
}
