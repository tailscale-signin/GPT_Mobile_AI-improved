package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.security.EndpointSecrets
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigurationRevisionTest {
    private val connection = ToolConnection("connection", "Private name", "private-alias", ToolConnectionType.MCP, "https://private.example/mcp", ToolConnectionAuthType.BEARER, "credential", null, createdAt = 1, updatedAt = 2)

    @Test fun encryptedRecordIdentityIsStableAcrossRestartsAndChangesAfterCredentialReplacement() = runBlocking {
        val vault = RevisionVault(mutableMapOf("credential" to "a".repeat(64)))
        val first = mcpConfigurationRevision(connection, vault)
        assertTrue(first?.matches(Regex("[a-f0-9]{64}")) == true)
        assertEquals(first, mcpConfigurationRevision(connection, RevisionVault(vault.revisions.toMutableMap())))
        assertEquals(first, mcpConfigurationRevision(connection, vault))
        vault.revisions["credential"] = "b".repeat(64)
        assertNotEquals(first, mcpConfigurationRevision(connection, vault))
        assertFalse(first.orEmpty().contains("private", ignoreCase = true))
        assertEquals(0, vault.plaintextReads)
    }

    @Test fun savedEndpointAndPermissionChangesCannotReuseAnEarlierConfigurationReceipt() = runBlocking {
        val vault = RevisionVault(mutableMapOf("credential" to "a".repeat(64)))
        val first = mcpConfigurationRevision(connection, vault)
        for (changed in listOf(
            connection.copy(endpointUrl = "https://different.example/mcp"),
            connection.copy(authType = ToolConnectionAuthType.API_KEY),
            connection.copy(allowCleartext = true),
            connection.copy(toolPolicy = "DENY_ALL"),
            connection.copy(approvedReadTools = "lookup"),
            connection.copy(updatedAt = 3)
        )) {
            assertNotEquals(first, mcpConfigurationRevision(changed, vault))
        }
        assertEquals(0, vault.plaintextReads)
    }

    @Test fun missingOrUnsupportedRecordRevisionStaysUnknownAndNeverFallsBackToPlaintext() = runBlocking {
        for (record in listOf(null, "invalid", "PRIVATE-CREDENTIAL")) {
            val vault = RevisionVault(mutableMapOf("credential" to record))
            assertEquals(null, mcpConfigurationRevision(connection, vault))
            assertEquals(0, vault.plaintextReads)
        }
        assertEquals(null, mcpConfigurationRevision(connection.copy(secretRef = null), RevisionVault()))
        val unauthenticated = connection.copy(authType = ToolConnectionAuthType.NONE, secretRef = null)
        assertTrue(mcpConfigurationRevision(unauthenticated, RevisionVault())?.matches(Regex("[a-f0-9]{64}")) == true)
    }

    @Test fun endpointQueryCredentialsRequireTheirOwnProtectedRecordRevision() = runBlocking {
        val unprotected = connection.copy(endpointUrl = "https://private.example/mcp?token=private-token")
        val vault = RevisionVault(mutableMapOf("credential" to "a".repeat(64)))
        assertEquals(null, mcpConfigurationRevision(unprotected, vault))
        val securedEndpoint = unprotected.copy(endpointUrl = EndpointSecrets.masked(requireNotNull(unprotected.endpointUrl)))
        assertEquals(null, mcpConfigurationRevision(securedEndpoint, vault))
        vault.revisions[EndpointSecrets.reference(connection.connectionUid)] = "c".repeat(64)
        val revision = mcpConfigurationRevision(securedEndpoint, vault)
        assertTrue(revision?.matches(Regex("[a-f0-9]{64}")) == true)
        vault.revisions[EndpointSecrets.reference(connection.connectionUid)] = "d".repeat(64)
        assertNotEquals(revision, mcpConfigurationRevision(securedEndpoint, vault))
        assertEquals(0, vault.plaintextReads)
    }

    @Test fun unauthenticatedConnectionsIgnoreUnusedAuthReferencesButStillBindProtectedEndpointRecords() = runBlocking {
        val unauthenticated = connection.copy(authType = ToolConnectionAuthType.NONE)
        val vault = RevisionVault()
        val revision = mcpConfigurationRevision(unauthenticated, vault)
        assertTrue(revision?.matches(Regex("[a-f0-9]{64}")) == true)
        assertEquals(revision, mcpConfigurationRevision(unauthenticated.copy(secretRef = null), vault))
        assertEquals(revision, mcpConfigurationRevision(unauthenticated.copy(secretRef = "another-unused-reference"), vault))
        vault.revisions["credential"] = "a".repeat(64)
        assertEquals(revision, mcpConfigurationRevision(unauthenticated, vault))
        assertTrue(vault.revisionReads.isEmpty())

        val securedEndpoint = unauthenticated.copy(endpointUrl = "https://private.example/mcp?token=__vault__")
        val endpointReference = EndpointSecrets.reference(connection.connectionUid)
        assertEquals(null, mcpConfigurationRevision(securedEndpoint, vault))
        vault.revisions[endpointReference] = "b".repeat(64)
        val protectedRevision = mcpConfigurationRevision(securedEndpoint, vault)
        assertTrue(protectedRevision?.matches(Regex("[a-f0-9]{64}")) == true)
        assertEquals(protectedRevision, mcpConfigurationRevision(securedEndpoint.copy(secretRef = null), vault))
        vault.revisions[endpointReference] = "c".repeat(64)
        assertNotEquals(protectedRevision, mcpConfigurationRevision(securedEndpoint, vault))
        assertTrue(vault.revisionReads.all { it == endpointReference })
        assertEquals(0, vault.plaintextReads)
    }

    private class RevisionVault(val revisions: MutableMap<String, String?> = mutableMapOf()) : SecretVault {
        var plaintextReads = 0
        val revisionReads = mutableListOf<String>()
        override suspend fun recordRevision(secretRef: String): String? {
            revisionReads += secretRef
            return revisions[secretRef]
        }
        override suspend fun read(secretRef: String): ByteArray? {
            plaintextReads++
            error("Configuration comparison must not read credentials")
        }
        override suspend fun put(secretRef: String, secret: ByteArray) = error("Read-only revision test")
        override suspend fun delete(secretRef: String) = error("Read-only revision test")
    }
}
