package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringCancellationTest {
    @Test(timeout = 15_000)
    fun cancellationClosesARealSocketWhileResponseHeadersAreStalled() = runBlocking {
        assertCancelled(Stall.HEADERS)
    }

    @Test(timeout = 15_000)
    fun cancellationRemainsAttachedWhileTheResponseBodyIsStalled() = runBlocking {
        assertCancelled(Stall.BODY)
    }

    @Test(timeout = 15_000)
    fun unauthorizedHttpResponseStillRejectsTheCredentialBeforeAnotherRequest() = runBlocking {
        StalledServer(Stall.UNAUTHORIZED).use { server ->
            val credential = "engineering-unauthorized-${UUID.randomUUID()}"
            val client = client(server)
            try {
                val api = GitHubEngineeringApi(client) { credential }
                val failure = runCatching { withTimeout(5000) { api.request("/user") } }.exceptionOrNull()
                assertEquals(401, (failure as? EngineeringApiException)?.status)
                assertTrue(GitHubSessionCaches.authenticationForCredential(credential).get())
                val repeated = runCatching { withTimeout(5000) { api.request("/user") } }.exceptionOrNull()
                assertTrue(repeated is IllegalStateException)
                assertTrue(repeated?.message.orEmpty().contains("authentication was rejected"))
                assertTrue(server.headers.await().contains("Authorization: Bearer $credential"))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private suspend fun assertCancelled(stall: Stall) = coroutineScope {
        StalledServer(stall).use { server ->
            val bodyStarted = CompletableDeferred<Unit>()
            val call = AtomicReference<Call>()
            val credential = "engineering-cancellation-${UUID.randomUUID()}"
            val client = client(
                server,
                object : EventListener() {
                    override fun callStart(observed: Call) {
                        call.set(observed)
                    }

                    override fun responseBodyStart(call: Call) {
                        bodyStarted.complete(Unit)
                    }
                }
            )
            val operation = async { GitHubEngineeringApi(client) { credential }.request("/repos/owner/repo/actions/runs/1") }
            try {
                val request = withTimeout(5000) { server.headers.await() }
                assertTrue(request.startsWith("GET /repos/owner/repo/actions/runs/1 HTTP/1.1"))
                assertTrue(request.contains("Authorization: Bearer $credential"))
                if (stall == Stall.BODY) withTimeout(5000) { bodyStarted.await() }
                // The transport's 15-second timeout cannot satisfy this assertion.
                withTimeout(3000) {
                    operation.cancel()
                    operation.join()
                    server.disconnected.await()
                }
                assertTrue(operation.isCancelled)
                assertTrue(requireNotNull(call.get()).isCanceled())
                assertTrue(!GitHubSessionCaches.authenticationForCredential(credential).get())
            } finally {
                operation.cancel()
                server.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun client(server: StalledServer, listener: EventListener = EventListener.NONE): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .eventListener(listener)
        .addInterceptor { chain ->
            // Only the test client rewrites the fixed production origin to its loopback fixture.
            chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.port}${chain.request().url.encodedPath}").build())
        }
        .build()

    private enum class Stall { HEADERS, BODY, UNAUTHORIZED }

    private class StalledServer(stall: Stall) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val accepted = AtomicReference<Socket>()
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "engineering-stalled-http-test").apply { isDaemon = true } }
        val port: Int get() = server.localPort
        val headers = CompletableDeferred<String>()
        val disconnected = CompletableDeferred<Unit>()

        init {
            executor.submit {
                try {
                    server.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = buildString {
                            repeat(64) {
                                val line = reader.readLine() ?: error("Request ended before its headers")
                                if (line.isEmpty()) return@buildString
                                appendLine(line)
                            }
                            error("Request header bound exceeded")
                        }
                        headers.complete(request)
                        if (stall == Stall.UNAUTHORIZED) {
                            socket.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                            return@use
                        }
                        if (stall == Stall.BODY) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\nContent-Type: application/json\r\n\r\n{".toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                        }
                        try {
                            check(reader.read() == -1) { "Expected the cancelled client to close its socket" }
                            disconnected.complete(Unit)
                        } catch (timeout: SocketTimeoutException) {
                            disconnected.completeExceptionally(timeout)
                        } catch (_: IOException) {
                            disconnected.complete(Unit)
                        }
                    }
                } catch (failure: Throwable) {
                    headers.completeExceptionally(failure)
                    disconnected.completeExceptionally(failure)
                }
            }
        }

        override fun close() {
            accepted.get()?.close()
            server.close()
            executor.shutdownNow()
        }
    }
}
