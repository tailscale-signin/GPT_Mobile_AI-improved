package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.repository.mcpOAuthPendingSecretRef
import dev.chungjungsoo.gptmobile.data.security.EndpointSecrets
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthRecoveryBindingTest {
    @Test fun callbackForAnEditedConnectionCannotExchangeOrSaveTheOldGrant() = runBlocking {
        val edits: List<(ToolConnection) -> ToolConnection> = listOf(
            { it.copy(endpointUrl = "https://changed.example/mcp") },
            { it.copy(oauthClientId = "changed-client") },
            { it.copy(allowCleartext = true) }
        )
        for (edit in edits) {
            Fixture().use { fixture ->
                val callback = fixture.begin()
                fixture.dao.current = edit(fixture.dao.current)
                val failure = runCatching { fixture.coordinator.complete("saved", callback) }.exceptionOrNull()
                assertChangedWithoutCredential(fixture, failure)
                assertEquals(0, fixture.tokenRequests)
            }
        }
    }

    @Test fun endpointCredentialEditWithTheSameMaskedRowAlsoRejectsThePendingGrant() = runBlocking {
        Fixture(endpoint = "https://resource.example/mcp?key=original").use { fixture ->
            val callback = fixture.begin()
            val before = fixture.dao.current
            fixture.dao.current = EndpointSecrets.protect(before.copy(endpointUrl = "https://resource.example/mcp?key=replaced"), fixture.vault)
            assertEquals(before, fixture.dao.current)
            val failure = runCatching { fixture.coordinator.complete("saved", callback) }.exceptionOrNull()
            assertChangedWithoutCredential(fixture, failure)
            assertEquals(0, fixture.tokenRequests)
        }
    }

    @Test fun connectionEditedDuringTokenExchangeCannotReceiveTheReturnedCredential() = runBlocking {
        Fixture().use { fixture ->
            val callback = fixture.begin()
            fixture.onToken = { fixture.dao.current = fixture.dao.current.copy(endpointUrl = "https://changed.example/mcp") }
            val failure = runCatching { fixture.coordinator.complete("saved", callback) }.exceptionOrNull()
            assertChangedWithoutCredential(fixture, failure)
            assertEquals(1, fixture.tokenRequests)
        }
    }

    @Test fun unchangedConnectionCompletesReauthorizationIncludingDynamicClientRegistration() = runBlocking {
        for (clientId in listOf("client", null)) {
            Fixture(clientId = clientId).use { fixture ->
                fixture.dao.current = fixture.dao.current.copy(secretRef = "connection_saved")
                val rejected = McpOAuthCredential("client", "https://auth.example/token", "https://resource.example/mcp", "expired-access", "Bearer", requiresReauthorization = true)
                fixture.vault.put("connection_saved", NetworkClient.json.encodeToString(rejected).encodeToByteArray())
                val callback = fixture.begin()
                fixture.coordinator.complete("saved", callback)
                assertEquals(1, fixture.tokenRequests)
                assertEquals("client", fixture.dao.current.oauthClientId)
                val bytes = requireNotNull(fixture.vault.read(requireNotNull(fixture.dao.current.secretRef)))
                val credential = NetworkClient.json.decodeFromString<McpOAuthCredential>(bytes.decodeToString())
                assertEquals("replacement-access", credential.accessToken)
                assertFalse(credential.requiresReauthorization)
                assertEquals("https://resource.example/mcp", credential.resource)
                assertEquals(null, fixture.vault.read(mcpOAuthPendingSecretRef("saved")))
                assertEquals("Bearer replacement-access", fixture.coordinator.authorizationHeader(fixture.dao.current))
            }
        }
    }

    @Test fun cancellationDuringTokenExchangePreservesPendingAuthorizationWithoutSavingCredential() = runBlocking {
        Fixture().use { fixture ->
            val callback = fixture.begin()
            fixture.onToken = { throw CancellationException("Cancelled by the sign-in owner") }
            val failure = runCatching { fixture.coordinator.complete("saved", callback) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(null, fixture.dao.current.secretRef)
            assertTrue(fixture.vault.read(mcpOAuthPendingSecretRef("saved")) != null)
            assertEquals(1, fixture.tokenRequests)
        }
    }

    @Test fun oldUnboundPendingRecordsRequireANewSignInWithoutExposingTheirSecret() = runBlocking {
        Fixture().use { fixture ->
            val old = McpOAuthPending("saved", "client", "https://auth.example/token", "https://resource.example/mcp", mcpOAuthRedirectUri("saved"), "private-state", "private-verifier")
            fixture.vault.put(mcpOAuthPendingSecretRef("saved"), NetworkClient.json.encodeToString(old).encodeToByteArray())
            val failure = runCatching { fixture.coordinator.complete("saved", mcpOAuthRedirectUri("saved") + "?code=private-code&state=private-state") }.exceptionOrNull()
            assertTrue(failure is McpOAuthException)
            assertTrue(failure?.message.orEmpty().contains("Start sign-in again"))
            assertFalse(failure?.message.orEmpty().contains("private"))
            assertEquals(null, failure?.cause)
            assertEquals(0, fixture.tokenRequests)
            assertEquals(null, fixture.dao.current.secretRef)
        }
    }

    private fun assertChangedWithoutCredential(fixture: Fixture, failure: Throwable?) {
        assertTrue(failure is McpLocalValidationException)
        assertEquals("CONNECTION_CONFIGURATION_CHANGED", (failure as McpLocalValidationException).validationRule)
        assertEquals(null, fixture.dao.current.secretRef)
        assertFalse(fixture.vault.values.keys.any { it.startsWith("connection_") })
    }

    private class Fixture(endpoint: String = "https://resource.example/mcp", clientId: String? = "client") : AutoCloseable {
        val dao = Connections(ToolConnection("saved", "Saved", "saved", ToolConnectionType.MCP, endpoint, ToolConnectionAuthType.OAUTH, null, clientId))
        val vault = Vault()
        var tokenRequests = 0
        var onToken: suspend () -> Unit = {}
        private val http = HttpClient(
            MockEngine { request ->
                val body = when (request.url.encodedPath) {
                    "/mcp" -> return@MockEngine respond("", HttpStatusCode.Unauthorized)
                    "/.well-known/oauth-protected-resource/mcp" -> """{"resource":"https://resource.example/mcp","authorization_servers":["https://auth.example"]}"""
                    "/.well-known/oauth-authorization-server" -> """{"issuer":"https://auth.example","authorization_endpoint":"https://auth.example/authorize","token_endpoint":"https://auth.example/token","registration_endpoint":"https://auth.example/register","code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["none"]}"""
                    "/register" -> """{"client_id":"client"}"""
                    "/token" -> {
                        tokenRequests++
                        onToken()
                        """{"access_token":"replacement-access","token_type":"Bearer","refresh_token":"replacement-refresh","expires_in":3600}"""
                    }
                    else -> error("Unexpected fixture request")
                }
                respond(body, headers = headersOf("Content-Type", "application/json"))
            }
        )
        private val manager = McpClientManager(http)
        val coordinator = McpOAuthCoordinator(McpOAuthClient(http), ToolConnectionRepository(dao, vault), vault, manager)

        suspend fun begin(): String {
            val authorization = coordinator.begin("saved", mcpOAuthRedirectUri("saved"))
            return mcpOAuthRedirectUri("saved") + "?code=fixture-code&state=" + Url(authorization).parameters["state"]
        }

        override fun close() {
            http.close()
        }
    }

    private class Vault : SecretVault {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String): ByteArray? = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
        override suspend fun references(): Set<String> = values.keys.toSet()
    }

    private class Connections(var current: ToolConnection) : ToolConnectionDao {
        override fun observeConnections() = flowOf(listOf(current))
        override suspend fun listConnections() = listOf(current)
        override suspend fun getAllConnections() = listOf(current)
        override suspend fun getConnection(connectionUid: String) = current.takeIf { it.connectionUid == connectionUid }
        override suspend fun getConnectionsByUids(connectionUids: List<String>) = listOf(current).filter { it.connectionUid in connectionUids }
        override suspend fun upsertConnection(connection: ToolConnection) {
            current = connection
        }
        override suspend fun deleteConnectionByUid(connectionUid: String) = error("Not used by this OAuth test")
        override suspend fun listBindingsByProfile(profileUid: String): List<AgentToolBinding> = emptyList()
        override suspend fun insertBinding(binding: AgentToolBinding) = error("Not used by this OAuth test")
        override suspend fun deleteConnectionToolBindingsForTypes(profileUid: String, toolName: String, connectionTypes: List<String>) = error("Not used by this OAuth test")
        override suspend fun deleteBuiltInToolBinding(profileUid: String, toolName: String) = error("Not used by this OAuth test")
        override suspend fun deleteConnectionBindingsForType(profileUid: String, connectionType: String) = error("Not used by this OAuth test")
    }
}
