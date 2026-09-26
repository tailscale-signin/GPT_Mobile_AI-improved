package dev.chungjungsoo.gptmobile.data.security

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointSecretsTest {
    @Test
    fun secretsLeaveConnectionRowsAndDiagnosticsButExecutionGetsOriginalUrl() = runBlocking {
        val sentinel = "SENTINEL_DO_NOT_EXPORT"
        val url = "https://example.com/mcp?api_key=$sentinel&region=us"
        val storage = object : SecretVault {
            val entries = mutableMapOf<String, ByteArray>()
            override suspend fun put(secretRef: String, secret: ByteArray) {
                entries[secretRef] = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = entries[secretRef]?.copyOf()
            override suspend fun delete(secretRef: String) {
                entries.remove(secretRef)
            }
        }
        val connection = ToolConnection("c", "Server", "server", "MCP", url, "NONE", null, null)
        val protected = EndpointSecrets.protect(connection, storage)
        assertFalse(protected.toString().contains(sentinel))
        assertEquals(url, EndpointSecrets.resolve(protected, storage))
        assertFalse(DiagnosticRedactor.redact("GET $url").contains(sentinel))
        val preview = DiagnosticRedactor.arguments(
            buildJsonObject {
                put("token", sentinel)
                put("url", url)
                put("path", "notes.txt")
            }
        )
        assertFalse(preview.contains(sentinel))
        assertTrue(preview.contains("notes.txt"))
        storage.entries.clear()
        assertTrue(runCatching { EndpointSecrets.resolve(protected, storage) }.isFailure)
        assertEquals(protected, EndpointSecrets.protect(protected, storage))
    }
}
