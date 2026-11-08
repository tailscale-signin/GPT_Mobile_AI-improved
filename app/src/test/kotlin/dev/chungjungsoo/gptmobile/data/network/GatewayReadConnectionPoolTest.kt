package dev.chungjungsoo.gptmobile.data.network

import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GatewayReadConnectionPoolTest {
    @Test fun gatewayMetadataEvictionRetainsTheOrdinaryConnectionAndReopensOnlyTheReadSocket() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = exchange.remoteAddress.port.toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val network = NetworkClient(OkHttp)
        val ordinary = network()
        val reads = network.gatewayReadClient()
        try {
            val url = "http://127.0.0.1:${server.address.port}/read"
            val ordinaryBefore = ordinary.prepareGet(url).execute { it.bodyAsText() }
            val metadataBefore = reads.prepareGet(url).execute { it.bodyAsText() }
            assertNotEquals(ordinaryBefore, metadataBefore)

            network.evictIdleConnections()

            val ordinaryAfter = ordinary.prepareGet(url).execute { it.bodyAsText() }
            val metadataAfter = reads.prepareGet(url).execute { it.bodyAsText() }
            assertEquals(ordinaryBefore, ordinaryAfter)
            assertNotEquals(metadataBefore, metadataAfter)
        } finally {
            ordinary.close()
            reads.close()
            server.stop(0)
        }
    }
}
