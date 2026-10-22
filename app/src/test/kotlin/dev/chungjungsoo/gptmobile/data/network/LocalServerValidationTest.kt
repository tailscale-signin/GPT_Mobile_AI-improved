package dev.chungjungsoo.gptmobile.data.network

import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServerValidationTest {
    @Test fun anonymousOpenAiAndOllamaProbeTheirActualApiWithoutGatewayMetadata() = runBlocking {
        val paths = ConcurrentLinkedQueue<String>()
        val credentials = ConcurrentLinkedQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            exchange.requestHeaders.getFirst("Authorization")?.let(credentials::add)
            val body = if (exchange.requestURI.path == "/api/tags") """{"models":[{"name":"local-test"}]}""" else """{"data":[{"id":"local-test"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            for ((type, url) in listOf(ClientType.CUSTOM to base, ClientType.CUSTOM to "$base/v1/", ClientType.OLLAMA to "$base/v1/")) {
                val result = ApiKeyValidator.validate(type, url, "")
                assertTrue(result.toString(), result is ApiKeyValidator.ValidationResult.Success)
            }
            assertEquals(listOf("/v1/models", "/v1/models", "/api/tags"), paths.toList())
            assertTrue(credentials.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test fun anonymousProbeRejectsOversizedOrNonCatalogSuccessBodies() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = if (exchange.requestURI.path.startsWith("/large/")) ByteArray(2_000_001) { 32 } else """{"status":"OK"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val invalid = ApiKeyValidator.validate(ClientType.CUSTOM, "$base/v1", "")
            val large = ApiKeyValidator.validate(ClientType.CUSTOM, "$base/large/v1", "")
            assertTrue(invalid.toString(), invalid is ApiKeyValidator.ValidationResult.Error && invalid.message.contains("compatible model catalog"))
            assertTrue(large.toString(), large is ApiKeyValidator.ValidationResult.Error && large.message.contains("too large"))
        } finally {
            server.stop(0)
        }
    }
}
