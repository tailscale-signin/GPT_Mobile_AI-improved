package dev.chungjungsoo.gptmobile.data.github

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBlobContentTest {
    @Test fun validUtf8RetainsByteCount() {
        val source = "name: Déploiement"
        val decoded = GitHubBlobContent.decode("base64", Base64.getEncoder().encodeToString(source.encodeToByteArray()))
        assertEquals(source, decoded.content)
        assertEquals(source.encodeToByteArray().size, decoded.bytes)
    }

    @Test fun malformedBinaryAndUnsupportedContentBecomeTypedPerFileOmissions() = runTest {
        val invalid = listOf(
            "base64" to "a",
            "base64" to Base64.getEncoder().encodeToString(byteArrayOf(0, 1)),
            "base64" to Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(), 0x28)),
            "binary" to "unsupported"
        )
        for ((encoding, content) in invalid) {
            val definition = GitHubWorkflowDefinitionCapture.capture { GitHubBlobContent.decode(encoding, content).content }
            assertEquals(JsonPrimitive(false), definition["metadata_available"])
            assertEquals(JsonPrimitive("GITHUB_BLOB_CONTENT_INVALID"), definition["error_code"])
        }
    }

    @Test fun oversizedDeclaredAndDecodedContentHaveBoundedOmissionReason() = runTest {
        val declared = GitHubWorkflowDefinitionCapture.capture {
            GitHubBlobContent.checkSize(2L * 1024 * 1024 + 1)
            error("Oversized blob must not be fetched")
        }
        val decoded = GitHubWorkflowDefinitionCapture.capture { GitHubBlobContent.decode("utf-8", "x".repeat(2 * 1024 * 1024 + 1)).content }
        assertEquals(JsonPrimitive("GITHUB_BLOB_CONTENT_LIMIT"), declared["error_code"])
        assertEquals(JsonPrimitive("GITHUB_BLOB_CONTENT_LIMIT"), decoded["error_code"])
        assertTrue(runCatching { GitHubWorkflowDefinitionCapture.capture { throw IllegalArgumentException("identity mismatch") } }.isFailure)
    }
}
