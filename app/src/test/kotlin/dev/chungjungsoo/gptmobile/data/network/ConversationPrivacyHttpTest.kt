package dev.chungjungsoo.gptmobile.data.network

import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.privacy.MaskedProviderCall
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrivacyHttpTest {
    @Test fun sharedClientKeepsConcurrentConversationPoliciesThroughTheOkHttpDispatcher() = runBlocking {
        Server().use { server ->
            val client = NetworkClient(OkHttp)()
            val validated = CopyOnWriteArrayList<Int?>()
            try {
                val privateRead = async(
                    ConversationPrivacyContext(11, true) + RequestAuthorizationGuard("private") {
                        validated += ConversationPrivacyContext.currentOrNull()?.chatId
                    }
                ) { client.get("${server.url}/v1/models").bodyAsText() }
                val ordinaryWrite = async(
                    ConversationPrivacyContext(22, false) + RequestAuthorizationGuard("ordinary") {
                        validated += ConversationPrivacyContext.currentOrNull()?.chatId
                    }
                ) { client.post("${server.url}/chat/completions") { setBody("ordinary request") }.bodyAsText() }
                assertEquals(listOf("{}", "{}"), awaitAll(privateRead, ordinaryWrite))
                assertEquals(setOf(11, 22), validated.toSet())
                assertEquals(2, server.paths.size)
                assertTrue(server.internalHeaders.all { it == null })
                assertNull(ConversationPrivacyContext.currentOrNull())
            } finally {
                client.close()
            }
        }
    }

    @Test fun privateProviderRequestsCannotForgeAnOrdinaryOwnerOrUseTheMaskedClientWithoutItsScope() = runBlocking {
        Server().use { server ->
            val network = NetworkClient(OkHttp)
            val ordinaryClient = network()
            val maskedClient = network.maskedInferenceClient()
            try {
                withContext(ConversationPrivacyContext(31, true)) {
                    for (client in listOf(ordinaryClient, maskedClient)) {
                        val failure = runCatching {
                            client.post("${server.url}/chat/completions") {
                                header(ConversationPrivacyHttp.HEADER, "untrusted-ordinary-owner")
                                setBody("must stay local")
                            }
                        }.exceptionOrNull()
                        assertTrue(failure is PrivateMetadataDenied)
                    }
                }
                assertTrue(server.paths.isEmpty())
            } finally {
                ordinaryClient.close()
                maskedClient.close()
            }
        }
    }

    @Test fun maskedRequestsRevalidateTheirOriginalAuthorityAtPhysicalDispatch() = runBlocking {
        Server().use { server ->
            val client = NetworkClient(OkHttp).maskedInferenceClient()
            var validations = 0
            try {
                val guard = RequestAuthorizationGuard("retired-mask") {
                    assertEquals(41, ConversationPrivacyContext.currentOrNull()?.chatId)
                    assertTrue(ConversationPrivacyContext.currentOrNull()?.enabled == true)
                    validations++
                    error("fixture masking policy changed")
                }
                val failure = withContext(ConversationPrivacyContext(41, true) + MaskedProviderCall + guard) {
                    runCatching { client.post("${server.url}/chat/completions") { setBody("masked request") } }.exceptionOrNull()
                }
                assertTrue(failure is IOException)
                assertEquals(1, validations)
                assertTrue(server.paths.isEmpty())
            } finally {
                client.close()
            }
        }
    }

    @Test fun nativeTagsKeepTheirOwnerAcrossRedirectCopiesAndRestoreAnUnrelatedCallbackScope() {
        val owner = ConversationPrivacyContext(51, true)
        val unrelated = ConversationPrivacyContext(52, false)
        val lifetime = Job()
        val captured = CapturedRequestPrivacy.capture(
            owner + lifetime + RequestAuthorizationGuard("original") {
                assertSame(owner, ConversationPrivacyContext.currentOrNull())
            }
        )
        val ticket = ConversationPrivacyHttp.ticket(captured, lifetime)
        val unbound = Request.Builder().url("https://provider.example/v1/models").header(ConversationPrivacyHttp.HEADER, ticket).build()
        try {
            val bound = ConversationPrivacyHttp.bind(unbound)
            assertNull(bound.header(ConversationPrivacyHttp.HEADER))
            assertTrue(runCatching { ConversationPrivacyHttp.bind(unbound) }.exceptionOrNull() is IOException)
            val redirect = bound.newBuilder().url("https://other.example/v1/models").build()
            assertSame(captured, ConversationPrivacyHttp.policy(redirect))
            ConversationPrivacyContext.withCaptured(unrelated) {
                ConversationPrivacyHttp.policy(redirect).recheckAuthorization()
                assertSame(unrelated, ConversationPrivacyContext.currentOrNull())
                assertFalse(ConversationPrivacyContext.currentOrNull()?.enabled == true)
            }
            assertNull(ConversationPrivacyContext.currentOrNull())
        } finally {
            lifetime.cancel()
        }
    }

    @Test fun cancellationRetiresBothUnboundTicketsAndAlreadyQueuedNativeRequests() {
        val lifetime = Job()
        val captured = CapturedRequestPrivacy.capture(ConversationPrivacyContext(61, true) + lifetime)
        val ticket = ConversationPrivacyHttp.ticket(captured, lifetime)
        val request = Request.Builder().url("https://provider.example/v1/models").header(ConversationPrivacyHttp.HEADER, ticket).build()
        lifetime.cancel()
        assertTrue(runCatching { ConversationPrivacyHttp.bind(request) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { captured.recheckAuthorization() }.exceptionOrNull() is IOException)
    }

    private class Server : AutoCloseable {
        val paths = CopyOnWriteArrayList<String>()
        val internalHeaders = CopyOnWriteArrayList<String?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                paths += exchange.requestURI.path
                internalHeaders += exchange.requestHeaders.getFirst(ConversationPrivacyHttp.HEADER)
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }
}
