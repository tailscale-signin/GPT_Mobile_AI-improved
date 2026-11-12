package dev.chungjungsoo.gptmobile.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataReadLoggingTest {
    @Test fun ktorFailureAndRetryLogsCarryTheExactBoundedReadIdentity() = runTest {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val attempts = mutableListOf<LocalMetadataReadAttempt>()
        var calls = 0
        HttpClient(
            MockEngine {
                if (++calls == 1) throw EOFException("unexpected end of stream")
                respond("ready")
            }
        ) {
            install(Logging) {
                level = LogLevel.HEADERS
                logger = object : Logger {
                    override fun log(message: String) {
                        logs += LocalMetadataReadAttempt.correlateLog(message)
                    }
                }
            }
        }.use { client ->
            val result = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29821/gateway/capabilities") {
                attempts += requireNotNull(currentCoroutineContext()[LocalMetadataReadAttempt])
                client.get("http://127.0.0.1:29821/gateway/capabilities").bodyAsText()
            }
            assertEquals("ready", result)
            assertEquals(2, calls)
            val failure = logs.single { "failed with exception" in it }
            assertTrue(failure.contains("incident_id=${attempts.first().readId}"))
            assertTrue(failure.contains("operation_id=${attempts.first().readId}"))
            assertTrue(failure.contains("retry_attempt=0"))
            assertEquals(attempts.first().readId, attempts.last().readId)
            assertTrue(logs.any { "retry_attempt=1" in it && "REQUEST:" in it })
        }
        assertEquals("outside", LocalMetadataReadAttempt.correlateLog("outside"))
    }

    @Test fun concurrentReadsNeverBorrowEachOthersLogIdentityAcrossThreadSwitches() = runTest {
        val results = (1..2).map { number ->
            async(LocalMetadataReadAttempt(0, number.toString().repeat(32))) {
                withContext(Dispatchers.Default) {
                    repeat(20) {
                        yield()
                        val log = LocalMetadataReadAttempt.correlateLog("same endpoint")
                        assertTrue(log.contains("incident_id=${number.toString().repeat(32)}"))
                        assertFalse(log.contains("incident_id=${(3 - number).toString().repeat(32)}"))
                    }
                    LocalMetadataReadAttempt.correlateLog("done")
                }
            }
        }.awaitAll()
        assertEquals(2, results.distinct().size)
        assertEquals("outside", LocalMetadataReadAttempt.correlateLog("outside"))
    }
}
