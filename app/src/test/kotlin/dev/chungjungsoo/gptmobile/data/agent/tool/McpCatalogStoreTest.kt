package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCatalogStoreTest {
    private class Vault : SecretVault {
        val records = mutableMapOf<String, ByteArray>()
        var rejectManifests = false
        override suspend fun put(secretRef: String, secret: ByteArray) {
            require(secretRef.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            require(secret.size <= 64 * 1024)
            check(!rejectManifests || !secretRef.startsWith("mcp_catalog_m_"))
            records[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = records[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            records.remove(secretRef)
        }
        override suspend fun references() = records.keys.toSet()
    }

    private fun tool(name: String, description: String = "metadata"): Tool = Json.decodeFromString(
        """{"name":"$name","description":"$description","inputSchema":{"type":"object","properties":{}}}"""
    )

    @Test fun restartRestoresValidatedSchemasOnlyForExactIdentity() = runBlocking {
        val vault = Vault()
        McpCatalogStore(vault).save("endpoint", "endpoint-credential-version", listOf(tool("read")), 100)
        val restarted = McpCatalogStore(vault)
        assertEquals(listOf("read"), restarted.load("endpoint", "endpoint-credential-version", 200)?.map { it.name })
        assertNull(restarted.load("endpoint", "changed-credential", 200))
        assertNull(restarted.load("other-connection", "endpoint-credential-version", 200))
        assertNull(restarted.load("endpoint", "endpoint-credential-version", 100 + 24 * 60 * 60_000L + 1))
        assertNull(restarted.load("endpoint", "endpoint-credential-version", 99))
    }

    @Test fun largeCatalogueChunksStayWithinVaultBoundsAndRetainSchemas() = runBlocking {
        val vault = Vault()
        val store = McpCatalogStore(vault)
        val tools = (1..6).map { tool("read$it", "x".repeat(30_000)) }
        store.save("endpoint", "identity", tools, 100)
        assertTrue(vault.records.keys.count { it.startsWith("mcp_catalog_c_") } > 1)
        assertEquals(tools.map { it.name }, store.load("endpoint", "identity", 100)?.map { it.name })
        val first = vault.records.keys.first { it.startsWith("mcp_catalog_c_") }
        vault.records[first] = byteArrayOf(1)
        assertNull(store.load("endpoint", "identity", 100))
    }

    @Test fun failedReplacementCannotPublishAnIncompleteSnapshot() = runBlocking {
        val vault = Vault()
        val store = McpCatalogStore(vault)
        store.save("endpoint", "identity", listOf(tool("old")), 100)
        vault.rejectManifests = true
        assertTrue(runCatching { store.save("endpoint", "identity", listOf(tool("new")), 200) }.isFailure)
        assertEquals(listOf("old"), store.load("endpoint", "identity", 200)?.map { it.name })
    }

    @Test fun retentionIsBoundedAndDoesNotDeleteCredentials() = runBlocking {
        val vault = Vault()
        vault.put("credential", byteArrayOf(42))
        val store = McpCatalogStore(vault)
        repeat(20) { store.save("connection$it", "identity$it", listOf(tool("read")), it.toLong()) }
        assertEquals(16, vault.records.keys.count { it.startsWith("mcp_catalog_m_") })
        assertEquals(16, vault.records.keys.count { it.startsWith("mcp_catalog_c_") })
        assertNotNull(vault.read("credential"))
        assertNull(store.load("connection0", "identity0", 20))
        assertNotNull(store.load("connection19", "identity19", 20))
        assertFalse(runCatching { store.save("duplicate", "identity", listOf(tool("read"), tool("read")), 20) }.isSuccess)
    }
}
