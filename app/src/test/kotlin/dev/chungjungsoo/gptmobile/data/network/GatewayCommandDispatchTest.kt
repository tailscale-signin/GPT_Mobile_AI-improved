package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent as MessageText
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real serializer/admission/HTTP path, with only the transport and disk callback replaced. */
class GatewayCommandDispatchTest {
    private val host = "gateway-command-${java.util.UUID.randomUUID()}.fixture.invalid"
    private val config = ProviderRequestConfig(
        "https://$host/v1", null, providerType = "OLLAMA", profileUid = "selected-profile",
        extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "false"),
        queueScope = "chat:message:run", queuePersistent = true, gatewayConversationKey = "stable-chat", contextCeiling = 8192
    )
    private val request = ChatCompletionRequest(
        model = "selected-model",
        messages = listOf(ChatMessage(role = Role.USER, content = listOf(MessageText("Saved request")))),
        stream = false,
        maxTokens = 256
    )
    private val capabilities = """{"gatewayVersion":"15.0.0","features":{"conversation_sync_v1":true},"accepted_request_headers":["X-Gateway-Allow-Local-Tools"]}"""
    private val answer = """{"choices":[{"message":{"content":"Saved answer"},"finish_reason":"stop"}]}"""

    @org.junit.Before fun resetConnectivity() = LocalServiceHealth.networkChanged()

    @org.junit.After fun clearConnectivity() = LocalServiceHealth.networkChanged()

    @Test fun reservationMustFinishBeforeFirstModelPostAndUsesTheWireCommand() = runTest {
        val entered = CompletableDeferred<String>()
        val commit = CompletableDeferred<Unit>()
        val posts = AtomicInteger()
        val wireCommands = Collections.synchronizedList(mutableListOf<String>())
        val selected = config.copy(onGatewayCommandPrepared = { command ->
            entered.complete(command)
            commit.await()
        })
        withApi(
            MockEngine { incoming ->
                if (incoming.method.value == "GET") {
                    assertTrue(incoming.url.encodedPath.endsWith("/gateway/capabilities"))
                    respond(capabilities)
                } else {
                    assertTrue(commit.isCompleted)
                    val metadata = Json.parseToJsonElement((incoming.body as TextContent).text).jsonObject.getValue("gateway").jsonObject
                    wireCommands += metadata.getValue("command_id").jsonPrimitive.content
                    posts.incrementAndGet()
                    respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        ) { api ->
            val result = async { api.streamChatCompletion(request, 5, selected).toList() }
            val reserved = entered.await()
            assertEquals(0, posts.get())
            commit.complete(Unit)
            assertTrue(result.await().none { it.error != null })
            assertEquals(listOf(reserved), wireCommands)
            assertEquals(1, posts.get())
        }
    }

    @Test fun failedLocalReservationNeverPostsAModelRequestOrClaimsAdmission() = runTest {
        val reservations = AtomicInteger()
        val posts = AtomicInteger()
        val selected = config.copy(onGatewayCommandPrepared = {
            reservations.incrementAndGet()
            error("Local reservation failed before sending")
        })
        withApi(
            MockEngine { incoming ->
                if (incoming.method.value == "GET") {
                    respond(capabilities)
                } else {
                    posts.incrementAndGet()
                    error("Model POST must remain blocked")
                }
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, selected).toList()
            assertEquals(1, reservations.get())
            assertEquals(0, posts.get())
            assertTrue(chunks.last().error?.message.orEmpty().contains("Local reservation failed"))
            assertTrue(chunks.none { it.gatewayMetadata?.jobId != null })
        }
    }

    @Test fun missingReservationWiringCannotCreateAnUntrackedPersistentJob() = runTest {
        val posts = AtomicInteger()
        withApi(
            MockEngine { incoming ->
                if (incoming.method.value == "GET") {
                    respond(capabilities)
                } else {
                    posts.incrementAndGet()
                    error("Model POST must remain blocked")
                }
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, config).toList()
            assertEquals(0, posts.get())
            assertTrue(chunks.last().error?.message.orEmpty().contains("no model request was sent"))
        }
    }

    @Test fun lostTransportReplyRetriesTheSameReservedCommandAndPayload() = runTest {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val selected = config.copy(onGatewayCommandPrepared = { events += "reserve:$it" })
        withApi(
            MockEngine { incoming ->
                if (incoming.method.value == "GET") {
                    respond(capabilities)
                } else {
                    val body = (incoming.body as TextContent).text
                    val command = Json.parseToJsonElement(body).jsonObject.getValue("gateway").jsonObject.getValue("command_id").jsonPrimitive.content
                    bodies += body
                    events += "post:$command"
                    if (bodies.size == 1) throw SocketException("Connection reset by peer")
                    respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        ) { api ->
            val chunks = api.streamChatCompletion(request, 5, selected).toList()
            assertTrue(chunks.none { it.error != null })
            assertEquals(2, bodies.size)
            assertEquals(bodies.first(), bodies.last())
            val command = events.first().substringAfter(':')
            assertEquals(listOf("reserve:$command", "post:$command", "reserve:$command", "post:$command"), events)
        }
    }

    @Test fun privateAndInternalRequestsDisableStorageAndNeverReserve() = runTest {
        for ((index, selected) in listOf(config.copy(queuePersistent = false), config.copy(requestRole = "github-review")).withIndex()) {
            val reservations = AtomicInteger()
            val posts = AtomicInteger()
            withApi(
                MockEngine { incoming ->
                    if (incoming.method.value == "GET") {
                        respond(capabilities)
                    } else {
                        val metadata = Json.parseToJsonElement((incoming.body as TextContent).text).jsonObject.getValue("gateway").jsonObject
                        assertEquals(JsonPrimitive(false), metadata["store_conversation"])
                        assertFalse(metadata.containsKey("command_id"))
                        posts.incrementAndGet()
                        respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            ) { api ->
                val input = selected.copy(apiUrl = "https://private-$index.$host/v1", onGatewayCommandPrepared = { reservations.incrementAndGet() })
                assertTrue(api.streamChatCompletion(request, 5, input).toList().none { it.error != null })
                assertEquals(0, reservations.get())
                assertEquals(1, posts.get())
            }
        }
    }

    @Test fun directAndExplicitlyUnsupportedProvidersNeverReserveOrReceiveGatewayMetadata() = runTest {
        for ((index, provider) in listOf("OPENAI", "OLLAMA").withIndex()) {
            val reservations = AtomicInteger()
            val reads = AtomicInteger()
            val posts = AtomicInteger()
            withApi(
                MockEngine { incoming ->
                    if (incoming.method.value == "GET") {
                        reads.incrementAndGet()
                        respond("{}", HttpStatusCode.NotFound)
                    } else {
                        val payload = Json.parseToJsonElement((incoming.body as TextContent).text).jsonObject
                        assertFalse(payload.containsKey("gateway"))
                        posts.incrementAndGet()
                        respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            ) { api ->
                val input = config.copy(apiUrl = "https://direct-$index.$host/v1", providerType = provider, extraHeaders = emptyMap(), onGatewayCommandPrepared = { reservations.incrementAndGet() })
                assertTrue(api.streamChatCompletion(request, 5, input).toList().none { it.error != null })
                assertEquals(0, reservations.get())
                assertEquals(if (provider == "OLLAMA") 1 else 0, reads.get())
                assertEquals(1, posts.get())
            }
        }
    }

    @Test fun cancellationAfterLocalCommitStillPreventsModelDispatch() = runTest {
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        val posts = AtomicInteger()
        val selected = config.copy(onGatewayCommandPrepared = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                commit.await()
            }
        })
        withApi(
            MockEngine { incoming ->
                if (incoming.method.value == "GET") {
                    respond(capabilities)
                } else {
                    posts.incrementAndGet()
                    respond(answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        ) { api ->
            val pending = async { api.streamChatCompletion(request, 5, selected).toList() }
            entered.await()
            pending.cancel()
            commit.complete(Unit)
            pending.join()
            assertEquals(0, posts.get())
        }
    }

    private suspend fun withApi(engine: MockEngine, block: suspend (OpenAIAPI) -> Unit) {
        HttpClient(engine) { install(HttpTimeout) }.use { client ->
            val wrapper = mockk<NetworkClient>()
            every { wrapper() } returns client
            every { wrapper.gatewayReadClient() } returns client
            block(OpenAIAPIImpl(wrapper))
        }
    }
}
