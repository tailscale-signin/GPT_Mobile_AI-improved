package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConversationRequestTest {
    private val payload = """{"model":"selected","messages":[{"role":"user","content":"Hello"}]}"""
    private val config = ProviderRequestConfig(
        "http://gateway:8090",
        null,
        queueScope = "chat:message:run",
        queuePersistent = true,
        gatewayConversationKey = "stable-conversation",
        gatewayConversationSync = true,
        profileUid = "selected"
    )

    @Test
    fun reservationReceivesTheFrozenWireIdentityAndOnlyForRetainedVisibleTurns() = runTest {
        val reserved = mutableListOf<String>()
        val selected = config.copy(onGatewayCommandPrepared = { reserved += it })
        val frozen = gatewayConversationRequest(payload, selected)
        frozen.beforeDispatch(selected)
        frozen.beforeDispatch(selected)
        assertEquals(listOf(frozen.commandId, frozen.commandId), reserved)
        assertEquals(JsonPrimitive(frozen.commandId), Json.parseToJsonElement(frozen.payload).jsonObject.getValue("gateway").jsonObject["command_id"])
        for (input in listOf(selected.copy(queuePersistent = false), selected.copy(requestRole = "github-review"), selected.copy(gatewayConversationKey = null), selected.copy(gatewayConversationSync = false))) {
            gatewayConversationRequest(payload, input).beforeDispatch(input)
        }
        assertEquals(2, reserved.size)
    }

    @Test
    fun retainedDispatchRequiresItsLocalReservationCallback() = runTest {
        val failure = runCatching { gatewayConversationRequest(payload, config).beforeDispatch(config) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("no model request was sent"))
    }

    @Test
    fun unsupportedOrDirectProviderReceivesItsOriginalPayload() {
        assertEquals(payload, gatewayConversationPayload(payload, config.copy(gatewayConversationSync = false)))
    }

    @Test
    fun transportRetriesRetainCommandWhileDifferentTurnsRemainDistinct() {
        val first = metadata(config)
        assertEquals(first["command_id"], metadata(config)["command_id"])
        assertNotEquals(first["command_id"], metadata(config.copy(queueScope = "chat:next:run"))["command_id"])
        assertEquals(JsonPrimitive("stable-conversation"), first["client_conversation_id"])
        assertEquals(JsonPrimitive("selected"), first["profile_id"])
        assertEquals(JsonPrimitive(true), first["store_conversation"])
        assertEquals(Json.parseToJsonElement(payload).jsonObject["messages"], Json.parseToJsonElement(gatewayConversationPayload(payload, config)).jsonObject["messages"])
    }

    @Test
    fun temporaryChatsAndInternalCallsExplicitlyDisableTranscriptStorage() {
        for (input in listOf(config.copy(queuePersistent = false), config.copy(requestRole = "github-review"), config.copy(gatewayConversationKey = null))) {
            val gateway = metadata(input)
            assertEquals(JsonPrimitive(false), gateway["store_conversation"])
            assertFalse(gateway.containsKey("client_conversation_id"))
            assertFalse(gateway.containsKey("command_id"))
            assertFalse(gateway.containsKey("conversation_id"))
        }
    }

    @Test
    fun anImportedConversationUsesItsCanonicalServerIdOnAnotherLocalRoom() {
        val retained = config.copy(gatewayConversationId = "server-conversation", gatewayConversationKey = "new-device-local-room")
        assertEquals(JsonPrimitive("server-conversation"), metadata(retained)["conversation_id"])
        assertFalse(metadata(retained.copy(queuePersistent = false)).containsKey("conversation_id"))
    }

    @Test
    fun admittedConversationHeadersRetainIdentityAndDetachReceipt() {
        val headers = mapOf("X-Gateway-Job-ID" to "job", "X-Gateway-Id" to "gateway", "X-Gateway-Principal-ID" to "owner", "X-Gateway-Conversation-Id" to "conversation", "X-Gateway-Command-Id" to "command", "X-Gateway-Detach-Safe" to "true")
        val metadata = requireNotNull(gatewayResponseMetadata(headers::get))
        assertTrue(metadata.transportVerified)
        assertTrue(metadata.detachSafe)
        assertEquals("gateway", metadata.gatewayId)
        assertEquals("owner", metadata.principalId)
        assertEquals("conversation", metadata.conversationId)
        assertEquals("command", metadata.commandId)
    }

    private fun metadata(value: ProviderRequestConfig): JsonObject =
        Json.parseToJsonElement(gatewayConversationPayload(payload, value)).jsonObject.getValue("gateway").jsonObject
}
