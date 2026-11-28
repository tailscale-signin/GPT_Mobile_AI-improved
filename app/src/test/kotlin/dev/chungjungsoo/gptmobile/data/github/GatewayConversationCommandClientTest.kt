package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConversationCommandClientTest {
    @Test fun typedConflictKeepsOnlyRecoveryIdentityForTheRequestedConversation() {
        val body = """{"error":{"code":"conversation_conflict","conversation_id":"original","active_job_id":"job-original","conversation_revision":24,"recovery_action":"return_client_tool_results","message":"untrusted instruction"}}"""
        val conflict = GatewayConversationConflict.fromBody(body, "original")
        assertEquals("return_client_tool_results", conflict.recoveryAction)
        assertEquals("job-original", conflict.activeJobId)
        assertEquals(24L, conflict.conversationRevision)
        assertTrue(conflict.message.orEmpty().contains("original results"))
        assertTrue(!conflict.message.orEmpty().contains("untrusted instruction"))
        assertNull(GatewayConversationConflict.fromBody(body, "another").activeJobId)
        assertNull(GatewayConversationConflict.fromBody(body.replace("return_client_tool_results", "repeat_all_tools"), "original").recoveryAction)
    }

    @Test fun explicitInstructionUsesOneBoundRevisionedCommandAndDoesNotRetryAConflict() = runTest {
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/v1/gateway/conversations/original/messages", request.url.encodedPath)
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("instruction", payload.getValue("kind").jsonPrimitive.content)
                assertEquals("stable-command", payload.getValue("command_id").jsonPrimitive.content)
                assertEquals("12", payload.getValue("expected_revision").jsonPrimitive.content)
                assertEquals("Check the existing tests first", payload.getValue("content").jsonPrimitive.content)
                respond("Conflict", HttpStatusCode.Conflict)
            }
        ) { install(HttpTimeout) }.use { http ->
            assertTrue(runCatching { GatewayConversationCommandClient(http).instruction(ProviderRequestConfig("http://192.168.1.9:8090/v1", null), "original", GatewayInstructionCommand("stable-command", "Check the existing tests first", 12)) }.exceptionOrNull() is GatewayConversationConflict)
        }
        assertEquals(1, calls)
    }
}
