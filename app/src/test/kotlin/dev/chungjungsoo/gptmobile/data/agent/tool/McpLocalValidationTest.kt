package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpLocalValidationTest {
    @Test fun invalidEndpointAndHeaderFailBeforeAnyRequestWithOnlySafeRuleAndField() = runBlocking {
        val configurations = listOf(
            McpConnectionConfig("", "https://private.example/mcp", false) to McpLocalValidationRule.CONNECTION_ID_REQUIRED,
            McpConnectionConfig("connection", "https://private.example/invalid path?key=private-value", false) to McpLocalValidationRule.ENDPOINT_SYNTAX_INVALID,
            McpConnectionConfig("connection", "ftp://private.example/mcp", false) to McpLocalValidationRule.ENDPOINT_SCHEME_UNSUPPORTED,
            McpConnectionConfig("connection", "https:///mcp", false) to McpLocalValidationRule.ENDPOINT_HOST_REQUIRED,
            McpConnectionConfig("connection", "https://private-user:private-password@private.example/mcp", false) to McpLocalValidationRule.ENDPOINT_USER_INFO_FORBIDDEN,
            McpConnectionConfig("connection", "https://private.example/mcp#private-fragment", false) to McpLocalValidationRule.ENDPOINT_FRAGMENT_FORBIDDEN,
            McpConnectionConfig("connection", "https://private.example:65536/mcp", false) to McpLocalValidationRule.ENDPOINT_PORT_INVALID,
            McpConnectionConfig("connection", "http://private.example/mcp", false) to McpLocalValidationRule.CLEARTEXT_NOT_ALLOWED,
            McpConnectionConfig("connection", "https://private.example/mcp", false, " ") to McpLocalValidationRule.AUTHORIZATION_REQUIRED,
            McpConnectionConfig("connection", "https://private.example/mcp", false, "Bearer private-token\r\nX-Injected: private-value") to McpLocalValidationRule.AUTHORIZATION_FORMAT_INVALID,
            McpConnectionConfig("connection", "https://private.example/mcp", false, googleApiKey = "private-key") to McpLocalValidationRule.GOOGLE_API_KEY_CONFIGURATION_INVALID
        )
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                error("Local validation must not dispatch")
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client))
        try {
            for ((config, rule) in configurations) {
                val failure = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
                assertTrue("Expected $rule, got ${failure?.javaClass?.simpleName}", failure is McpLocalValidationException)
                assertEquals(rule, (failure as McpLocalValidationException).rule)
                val presentation = mcpFailurePresentation(failure, McpConnectionHealth())
                assertEquals(rule.diagnosticCode, presentation.errorCode)
                assertEquals(rule.name, presentation.validationRule)
                assertEquals(rule.field, presentation.validationField)
                assertEquals("local_validation", presentation.phase)
                assertEquals("NOT_DISPATCHED", presentation.dispatchEvidence)
                assertEquals("AFTER_CONFIGURATION_CHANGE", presentation.retryDecision)
                assertEquals(false, presentation.retryable)
                assertEquals(null, presentation.httpStatus)
                assertFalse(presentation.message.contains("private"))
                assertEquals(null, failure.cause)
            }
            assertEquals(0, requests)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    @Test fun rejectedBearerValuesNeverSurviveInExceptionsAndVaultBuffersAreAlwaysCleared() {
        for ((text, rule) in listOf(
            " " to McpLocalValidationRule.CREDENTIAL_REQUIRED,
            "private-value\n" to McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID,
            "private-value\r\nX-Injected: private" to McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID,
            "private-\u0000-value" to McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID,
            "private-\u0100-value" to McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID,
            "x".repeat(128 * 1024) to McpLocalValidationRule.AUTHORIZATION_TOO_LONG
        )) {
            val bytes = text.toByteArray()
            val failure = runCatching { mcpBearerHeader(bytes) }.exceptionOrNull()
            assertTrue(failure is McpLocalValidationException)
            assertEquals(rule, (failure as McpLocalValidationException).rule)
            assertTrue(bytes.all { it == 0.toByte() })
            assertFalse(failure.message.orEmpty().contains("private"))
            assertEquals(null, failure.cause)
        }
        val bytes = " valid-fixture-token ".toByteArray()
        assertEquals("Bearer valid-fixture-token", mcpBearerHeader(bytes))
        assertTrue(bytes.all { it == 0.toByte() })
    }
}
