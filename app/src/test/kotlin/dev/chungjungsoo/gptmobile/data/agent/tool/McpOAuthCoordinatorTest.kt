package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthCoordinatorTest {
    private fun connection(id: String) = ToolConnection(id, id, id, ToolConnectionType.MCP, "https://$id.example/mcp", ToolConnectionAuthType.OAUTH, id, "client")
    private fun credential(id: String) = McpOAuthCredential("client", "https://$id.example/token", "https://$id.example/mcp", "expired-$id", "Bearer", "refresh-$id", 1)
    private class Vault : SecretVault {
        val values = ConcurrentHashMap<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }

    @Test fun slowRefreshDoesNotBlockAnotherConnectionAndTransientFailureBacksOffIndependently() = runBlocking {
        val client = mockk<McpOAuthClient>()
        val repository = mockk<ToolConnectionRepository>(relaxed = true)
        val manager = mockk<McpClientManager>(relaxed = true)
        val vault = Vault()
        val connections = listOf(connection("slow"), connection("fast"), connection("failed"))
        connections.forEach { connection ->
            vault.put(connection.connectionUid, NetworkClient.json.encodeToString(credential(connection.connectionUid)).toByteArray())
            coEvery { repository.getConnection(connection.connectionUid) } returns connection
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { client.refresh(any()) } coAnswers {
            val saved = firstArg<McpOAuthCredential>()
            if (saved.accessToken == "expired-slow") {
                entered.complete(Unit)
                release.await()
            }
            if (saved.accessToken == "expired-failed") throw McpOAuthException("Temporary refresh failure")
            saved.copy(accessToken = "renewed", expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 3600)
        }
        val coordinator = McpOAuthCoordinator(client, repository, vault, manager)
        val slow = async { coordinator.authorizationHeader(connections[0]) }
        try {
            withTimeout(3000) { entered.await() }
            assertEquals("Bearer renewed", withTimeout(3000) { coordinator.authorizationHeader(connections[1]) })
            assertTrue(runCatching { coordinator.authorizationHeader(connections[2]) }.exceptionOrNull() is McpOAuthException)
            assertTrue(runCatching { coordinator.authorizationHeader(connections[2]) }.exceptionOrNull() is McpBackoffException)
            coVerify(exactly = 1) { client.refresh(match { it.accessToken == "expired-failed" }) }
        } finally {
            release.complete(Unit)
            slow.await()
        }
    }

    @Test fun missingCredentialRequiresActionableReauthorization() = runBlocking {
        val coordinator = McpOAuthCoordinator(mockk(), mockk(), Vault(), mockk(relaxed = true))
        val failure = runCatching { coordinator.authorizationHeader(connection("missing")) }.exceptionOrNull()
        assertTrue(failure is McpOAuthException && failure.requiresReauthorization && failure.message.orEmpty().contains("Plugins/Tools"))
    }
}
