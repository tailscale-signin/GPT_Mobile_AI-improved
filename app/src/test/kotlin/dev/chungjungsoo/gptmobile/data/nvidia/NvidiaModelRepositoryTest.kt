package dev.chungjungsoo.gptmobile.data.nvidia

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NvidiaModelRepositoryTest {
    @Test
    fun catalogCachesByCredentialAndCanForceRefresh() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/v1/models") { exchange ->
            calls.incrementAndGet()
            val bytes = """{"data":[{"id":"openai/gpt-oss-20b"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val repository = NvidiaModelRepository()
            val first = repository.load(url, "first")
            val second = repository.load(url, "first")
            repository.load(url, "second")
            repository.load(url, "first", forceRefresh = true)
            assertEquals(listOf("openai/gpt-oss-20b"), first.map { it.id })
            assertEquals(first, second)
            assertEquals(3, calls.get())
        } finally {
            server.stop(0)
        }
    }
}
