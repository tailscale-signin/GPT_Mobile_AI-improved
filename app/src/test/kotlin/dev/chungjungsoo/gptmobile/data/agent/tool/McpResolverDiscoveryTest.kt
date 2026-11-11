package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpResolverDiscoveryTest {
    private val connection = ToolConnection("connection", "Fixture", "fixture", ToolConnectionType.MCP, "https://fixture.example/mcp", ToolConnectionAuthType.OAUTH, "credential", "client")

    @Test fun explicitDiscoveryRefreshUsesModern401ToRefreshTheCredentialExactlyOnce() = runBlocking {
        val setup = setup()
        coEvery { setup.oauth.authorizationHeader(connection, false, null) } returns "Bearer expired"
        coEvery { setup.oauth.authorizationHeader(connection, true, "Bearer expired") } returns "Bearer renewed"
        var calls = 0
        coEvery { setup.manager.listToolsWithReceipt(any(), true, true, false) } coAnswers {
            val config = firstArg<McpConnectionConfig>()
            assertEquals(if (++calls == 1) "Bearer expired" else "Bearer renewed", config.authorizationHeader)
            if (calls == 1) throw ModernMcpError(401, null, "Rejected")
            McpDiscoveryReceipt(emptyList(), McpDiscoverySource.FRESH)
        }
        assertTrue(setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true).isEmpty())
        assertEquals(2, calls)
        coVerify(exactly = 1) { setup.oauth.authorizationHeader(connection, true, "Bearer expired") }
        coVerify(exactly = 0) { setup.manager.callTool(any(), any(), any(), any(), any()) }
    }

    @Test fun secondUnauthorizedResponseAndForbiddenResponseNeverLoopCredentialRefresh() = runBlocking {
        for (status in listOf(401, 403)) {
            val setup = setup()
            coEvery { setup.oauth.authorizationHeader(connection, false, null) } returns "Bearer expired"
            coEvery { setup.oauth.authorizationHeader(connection, true, "Bearer expired") } returns "Bearer renewed"
            coEvery { setup.manager.listToolsWithReceipt(any(), any(), true, false) } throws ModernMcpError(status, null, "Rejected")
            assertTrue(runCatching { setup.resolver.discoverMcpTools(connection, awaitCompletion = true) }.exceptionOrNull() is ModernMcpError)
            coVerify(exactly = if (status == 401) 1 else 0) { setup.oauth.authorizationHeader(connection, true, any()) }
            coVerify(exactly = if (status == 401) 2 else 1) { setup.manager.listToolsWithReceipt(any(), any(), true, false) }
            coVerify(exactly = 0) { setup.manager.callTool(any(), any(), any(), any(), any()) }
        }
    }

    @Test fun localEndpointAuthAndCredentialRejectionsNeverStartDiscoveryOrOAuthRefresh() = runBlocking {
        for ((invalid, rule) in listOf(
            connection.copy(endpointUrl = "https://private.example/invalid path?token=private-value") to McpLocalValidationRule.ENDPOINT_SYNTAX_INVALID,
            connection.copy(authType = "PRIVATE_UNSUPPORTED_AUTH") to McpLocalValidationRule.AUTH_TYPE_UNSUPPORTED,
            connection.copy(authType = ToolConnectionAuthType.BEARER, secretRef = null) to McpLocalValidationRule.CREDENTIAL_REQUIRED
        )) {
            val setup = setup()
            val failure = runCatching { setup.resolver.discoverMcpTools(invalid, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
            assertTrue(failure is McpLocalValidationException)
            assertEquals(rule, (failure as McpLocalValidationException).rule)
            assertEquals("NOT_DISPATCHED", failure.dispatchEvidence)
            assertFalse(failure.message.orEmpty().contains("private", ignoreCase = true))
            coVerify(exactly = 0) { setup.manager.listToolsWithReceipt(any(), any(), any(), any()) }
            coVerify(exactly = 0) { setup.oauth.authorizationHeader(any(), any(), any()) }
        }
    }

    private fun setup(): Setup {
        val manager = mockk<McpClientManager>()
        val oauth = mockk<McpOAuthCoordinator>()
        val vault = mockk<SecretVault>()
        coEvery { vault.recordRevision(any()) } returns null
        return Setup(AgentToolResolver(mockk(), mockk(), vault, mockk(), manager, oauth, mockk()), manager, oauth)
    }

    private data class Setup(val resolver: AgentToolResolver, val manager: McpClientManager, val oauth: McpOAuthCoordinator)
}
