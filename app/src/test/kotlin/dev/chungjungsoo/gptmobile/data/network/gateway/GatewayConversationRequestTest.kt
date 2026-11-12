package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    fun queuedGatewayRequestsRequireTheSavedConversationAndDispatchContracts() = runTest {
        val selected = config.copy(requireGatewayConversation = true, onGatewayCommandPrepared = {}, onGatewayCommandDispatching = {})
        gatewayConversationRequest(payload, selected).beforeDispatch(selected)
        for (invalid in listOf(
            selected.copy(gatewayConversationSync = false),
            selected.copy(queuePersistent = false),
            selected.copy(gatewayConversationKey = null),
            selected.copy(onGatewayCommandPrepared = null),
            selected.copy(onGatewayCommandDispatching = null)
        )) {
            val failure = runCatching { gatewayConversationRequest(payload, invalid) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("GATEWAY_QUEUE_DURABILITY_REQUIRED"))
        }
    }

    @Test
    fun isolatedVisibleTurnsKeepTheirDurableCommandWithoutEnablingServerTools() = runTest {
        for ((role, callerRole) in listOf("primary" to "primary", "primary" to "github-primary", "synthesis" to "synthesis")) {
            val reserved = mutableListOf<String>()
            val dispatched = mutableListOf<String>()
            val selected = config.copy(
                requestRole = role,
                maximizeReasoning = callerRole == "github-primary",
                requireGatewayConversation = true,
                extraHeaders = mapOf(
                    "X-Gateway-Request-Role" to callerRole,
                    "X-Gateway-Delegated-Worker" to "true",
                    "X-Gateway-Allow-Local-Tools" to "false"
                ),
                onGatewayCommandPrepared = { reserved += it },
                onGatewayCommandDispatching = { dispatched += it }
            )
            val frozen = gatewayConversationRequest(payload, selected)
            frozen.beforeDispatch(selected)
            frozen.armDispatch(selected)
            assertEquals(JsonPrimitive(true), metadata(selected)["store_conversation"])
            assertEquals(listOf(frozen.commandId), reserved)
            assertEquals(reserved, dispatched)
            assertEquals("false", selected.extraHeaders["X-Gateway-Allow-Local-Tools"])
            assertEquals(frozen.commandId, gatewayConversationRequest(payload, selected).commandId)
            for (privateTurn in listOf(
                selected.copy(queuePersistent = false),
                selected.copy(requestRole = "reviewer"),
                selected.copy(extraHeaders = selected.extraHeaders - "X-Gateway-Request-Role"),
                selected.copy(extraHeaders = selected.extraHeaders + ("X-Gateway-Request-Role" to "reviewer")),
                selected.copy(extraHeaders = selected.extraHeaders + ("X-Gateway-Request-Role" to "github-review"))
            )) {
                assertTrue(runCatching { gatewayConversationRequest(payload, privateTurn) }.isFailure)
            }
            if (callerRole == "github-primary") {
                assertTrue(runCatching { gatewayConversationRequest(payload, selected.copy(maximizeReasoning = false)) }.isFailure)
                assertTrue(runCatching { gatewayConversationRequest(payload, selected.copy(requestRole = "synthesis")) }.isFailure)
            }
        }
    }

    @Test
    fun cancellationAfterReservationRecordsTheExactUnsentCommandBeforePropagatingCancellation() = runTest {
        val reservations = mutableListOf<String>()
        val unsent = mutableListOf<String>()
        var wireAttempts = 0
        var dispatchArmed = false
        val selected = config.copy(
            requireGatewayConversation = true,
            onGatewayCommandPrepared = {
                reservations += it
                currentCoroutineContext().cancel()
            },
            onGatewayCommandNotDispatched = {
                assertTrue(currentCoroutineContext().isActive)
                unsent += it
            },
            onGatewayCommandDispatching = { dispatchArmed = true }
        )
        val frozen = gatewayConversationRequest(payload, selected)
        val attempt = launch {
            frozen.beforeDispatch(selected)
            frozen.armDispatch(selected)
            wireAttempts++
        }
        attempt.join()
        assertTrue(attempt.isCancelled)
        assertEquals(listOf(frozen.commandId), reservations)
        assertEquals(listOf(frozen.commandId), unsent)
        assertFalse(dispatchArmed)
        assertEquals(0, wireAttempts)
    }

    @Test
    fun aReservationFailureRetainsItsOriginalErrorAfterBestEffortUnsentRecording() = runTest {
        val original = IOException("reservation failed")
        val unsent = mutableListOf<String>()
        val selected = config.copy(
            onGatewayCommandPrepared = { throw original },
            onGatewayCommandNotDispatched = {
                unsent += it
                throw IOException("journal temporarily unavailable")
            }
        )
        val frozen = gatewayConversationRequest(payload, selected)
        assertEquals(original, runCatching { frozen.beforeDispatch(selected) }.exceptionOrNull())
        assertEquals(listOf(frozen.commandId), unsent)
    }

    @Test
    fun aDispatchBoundaryFailureCannotPublishUnsentProofForAnAlreadyArmedCommand() = runTest {
        val unsent = mutableListOf<String>()
        var armed: String? = null
        val selected = config.copy(
            onGatewayCommandPrepared = {},
            onGatewayCommandNotDispatched = { unsent += it },
            onGatewayCommandDispatching = {
                armed = it
                throw IOException("dispatch-state acknowledgement lost")
            }
        )
        val frozen = gatewayConversationRequest(payload, selected)
        frozen.beforeDispatch(selected)
        assertTrue(runCatching { frozen.armDispatch(selected) }.exceptionOrNull() is IOException)
        assertEquals(frozen.commandId, armed)
        assertTrue(unsent.isEmpty())
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
