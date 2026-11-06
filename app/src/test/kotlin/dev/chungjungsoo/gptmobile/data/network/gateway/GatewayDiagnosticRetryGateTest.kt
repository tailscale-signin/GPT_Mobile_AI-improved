package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GatewayDiagnosticRetryGateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val boot = "b".repeat(32)
    private val now = 1_800_000_000_000L
    private val config = ProviderRequestConfig("https://diagnostics.example/v1", "credential")

    private fun spool(): GatewayDiagnosticSpool = GatewayDiagnosticSpool(File(temporary.newFolder(), "capture.jsonl"), boot, { now }).also {
        it.append(listOf(now to buildJsonObject { put("stage", "request") }))
    }

    private fun ack(spool: GatewayDiagnosticSpool, through: Long = 1) = buildJsonObject {
        put("schema_version", 2)
        put("session_id", spool.sessionId)
        put("storage_state", "committed")
        put(
            "watermarks",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("origin_device_pseudonym", spool.originId)
                        put("origin_boot_id", boot)
                        put("accounted_through", through)
                    }
                )
            )
        )
    }

    private fun page(spool: GatewayDiagnosticSpool) = buildJsonObject {
        put("schema_version", 2)
        put("session_id", spool.sessionId)
        put("events", JsonArray(emptyList()))
        put("next_cursor", 0)
        put("last_ingest_sequence", 0)
    }

    private fun http(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout)
    }

    @Test fun rejectedAuthenticationAuthorizationAndProtocolHaveExactlyOnePhysicalAttempt() = runTest {
        val codes = mapOf(
            401 to "DIAGNOSTIC_AUTHENTICATION_REQUIRED", 403 to "DIAGNOSTIC_ACCESS_DENIED",
            404 to "DIAGNOSTIC_SOURCE_UNAVAILABLE", 405 to "DIAGNOSTIC_SOURCE_UNAVAILABLE",
            409 to "DIAGNOSTIC_CAPTURE_BLOCKED", 410 to "DIAGNOSTIC_CAPTURE_EXPIRED",
            400 to "DIAGNOSTIC_REQUEST_REJECTED", 413 to "DIAGNOSTIC_REQUEST_REJECTED", 422 to "DIAGNOSTIC_REQUEST_REJECTED",
            307 to "DIAGNOSTIC_REDIRECT_REJECTED", 501 to "DIAGNOSTIC_PROTOCOL_FAILURE"
        )
        for ((status, expected) in codes) {
            val spool = spool()
            var clock = 0L
            val gate = GatewayDiagnosticRetryGate { clock }
            var requests = 0
            http(
                MockEngine {
                    requests++
                    respond("private body must never become an error", HttpStatusCode.fromValue(status))
                }
            ).use { client ->
                repeat(12) {
                    gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, GatewayDiagnosticHttpTransport(client, config, allowed), allowed).once() }
                    clock += 1_000_000
                }
            }
            assertEquals("HTTP $status", 1, requests)
            assertEquals(GatewayDiagnosticRetryState.BLOCKED, gate.snapshot().state)
            assertEquals(expected, gate.snapshot().error)
            assertEquals(1, spool.status().pending)
            assertEquals(0L, spool.cursor)
        }
    }

    @Test fun transientFailuresHaveFiniteMonotonicBackoffAndExplicitRefreshReplaysOriginalIds() = runTest {
        for (failure in listOf(IOException("private transport error"), DiagnosticSyncFailure("DIAGNOSTIC_BACKPRESSURE"), DiagnosticSyncFailure("DEBUG_STORAGE_UNAVAILABLE"))) {
            val spool = spool()
            val session = spool.sessionId
            var clock = -1_000_000L // nanoTime has no wall-clock epoch and may be negative.
            val gate = GatewayDiagnosticRetryGate { clock }
            val sent = mutableListOf<JsonObject>()
            var reads = 0
            var recover = false
            val transport = object : GatewayDiagnosticTransport {
                override suspend fun post(batch: JsonObject): JsonObject {
                    sent += batch
                    if (!recover) throw failure
                    return ack(spool)
                }
                override suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject {
                    reads++
                    return page(spool)
                }
            }
            val delays = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
            repeat(6) { attempt ->
                assertFalse(gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() })
                assertEquals(attempt + 1, sent.size)
                if (attempt < 5) {
                    assertEquals(delays[attempt], gate.snapshot().retryAfterMillis)
                    clock += delays[attempt] - 1
                    repeat(3) { gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() } }
                    assertEquals(attempt + 1, sent.size)
                    clock++
                }
            }
            assertEquals(GatewayDiagnosticRetryState.EXHAUSTED, gate.snapshot().state)
            repeat(20) {
                clock += 60_000
                gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() }
            }
            assertEquals(6, sent.size)
            assertEquals(0, reads)
            assertEquals(1, spool.status().pending)
            recover = true
            assertTrue(gate.refresh(true))
            assertTrue(gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() })
            assertEquals(7, sent.size)
            assertEquals(1, reads)
            assertTrue(sent.all { it == sent.first() })
            assertEquals(session, spool.sessionId)
            assertEquals(0, spool.status().pending)
            assertNull(gate.snapshot().error)
            assertEquals(0, gate.snapshot().failures)
        }
    }

    @Test fun invalidAndNonAdvancingAcknowledgementsBlockWithoutDiscardingUnacknowledgedEvents() = runTest {
        for (kind in listOf("shape", "missing", "invalid", "wrong_owner", "stalled")) {
            val spool = spool()
            val before = spool.batch()
            val gate = GatewayDiagnosticRetryGate { 0L }
            var writes = 0
            var reads = 0
            val transport = object : GatewayDiagnosticTransport {
                override suspend fun post(batch: JsonObject): JsonObject {
                    writes++
                    return when (kind) {
                        "shape" -> buildJsonObject { put("storage_state", "committed") }
                        "missing" -> JsonObject(ack(spool) + ("watermarks" to JsonArray(emptyList())))
                        "invalid" -> JsonObject(ack(spool) + ("watermarks" to JsonArray(listOf(JsonPrimitive("invalid")))))
                        "wrong_owner" -> JsonObject(ack(spool) + ("session_id" to JsonPrimitive("f".repeat(32))))
                        else -> ack(spool, 0)
                    }
                }
                override suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject {
                    reads++
                    return page(spool)
                }
            }
            repeat(4) { gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() } }
            assertEquals(kind, 1, writes)
            assertEquals(0, reads)
            assertEquals("INVALID_DIAGNOSTIC_ACK", gate.snapshot().error)
            assertEquals(GatewayDiagnosticRetryState.BLOCKED, gate.snapshot().state)
            assertEquals(before, spool.batch())
            assertNull(spool.status().storageError)
            assertNull(spool.status().lastSyncAt)
        }
    }

    @Test fun invalidPageStopsPollingWithoutRollingBackAnAlreadyDurableAcknowledgement() = runTest {
        for (invalid in listOf("shape", "cursor")) {
            val spool = spool()
            val gate = GatewayDiagnosticRetryGate { 0L }
            var writes = 0
            var reads = 0
            val transport = object : GatewayDiagnosticTransport {
                override suspend fun post(batch: JsonObject): JsonObject {
                    writes++
                    return ack(spool)
                }
                override suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject {
                    reads++
                    return if (invalid == "shape") JsonObject(page(spool) - "events") else JsonObject(page(spool) + ("next_cursor" to JsonPrimitive(-1)))
                }
            }
            repeat(4) { gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() } }
            assertEquals(1, writes)
            assertEquals(1, reads)
            assertEquals(GatewayDiagnosticRetryState.BLOCKED, gate.snapshot().state)
            assertEquals(if (invalid == "shape") "INVALID_DIAGNOSTIC_PAGE" else "INVALID_DIAGNOSTIC_CURSOR", gate.snapshot().error)
            assertEquals(0, spool.status().pending)
            assertEquals(1, spool.status().retained)
            assertEquals(0L, spool.cursor)
            assertNull(spool.status().storageError)
        }
    }

    @Test fun malformedAndOversizedResponseBodiesStopAfterOneRequest() = runTest {
        for ((body, expected) in listOf("not JSON private secret" to "INVALID_DIAGNOSTIC_ACK", "[]" to "INVALID_DIAGNOSTIC_ACK", "x".repeat(128 * 1024 + 1) to "DIAGNOSTIC_RESPONSE_LIMIT")) {
            val spool = spool()
            val gate = GatewayDiagnosticRetryGate { 0L }
            var requests = 0
            http(
                MockEngine {
                    requests++
                    respond(body, HttpStatusCode.OK)
                }
            ).use { client ->
                repeat(4) { gate.run({ true }) { allowed -> GatewayDiagnosticSync(spool, GatewayDiagnosticHttpTransport(client, config, allowed), allowed).once() } }
            }
            assertEquals(1, requests)
            assertEquals(expected, gate.snapshot().error)
            assertEquals(GatewayDiagnosticRetryState.BLOCKED, gate.snapshot().state)
        }
    }

    @Test fun unknownExceptionsAndTlsFailuresAreNotRetriedOrExposedAsRawMessages() = runTest {
        for (failure in listOf(SSLHandshakeException("private certificate details"), IllegalArgumentException("private JSON body"), IllegalStateException("invalid bounds"), DiagnosticSyncFailure("UNKNOWN_FUTURE_PROTOCOL_ERROR"), SecurityException("private account"))) {
            val gate = GatewayDiagnosticRetryGate { 0L }
            var attempts = 0
            repeat(4) {
                gate.run({ true }) {
                    attempts++
                    throw failure
                }
            }
            assertEquals(1, attempts)
            assertEquals(GatewayDiagnosticRetryState.BLOCKED, gate.snapshot().state)
            assertFalse(gate.snapshot().error.orEmpty().contains("private"))
        }
    }

    @Test fun privacyPauseAndDisallowedRefreshKeepFailureBudgetAndPreserveUncommittedSpool() = runTest {
        val spool = spool()
        val before = spool.batch()
        var clock = 0L
        var permitted = true
        val gate = GatewayDiagnosticRetryGate { clock }
        gate.run({ permitted }) { throw IOException("transient") }
        clock += 2_000
        var writes = 0
        var reads = 0
        val transport = object : GatewayDiagnosticTransport {
            override suspend fun post(batch: JsonObject): JsonObject {
                writes++
                permitted = false
                gate.pause()
                return ack(spool)
            }
            override suspend fun read(session: String, after: Long, excludeOrigin: String): JsonObject {
                reads++
                return page(spool)
            }
        }
        assertFalse(gate.run({ permitted }) { allowed -> GatewayDiagnosticSync(spool, transport, allowed).once() })
        assertFalse(gate.refresh(permitted))
        repeat(4) { gate.run({ permitted }) { error("Private capture must never dispatch") } }
        assertEquals(1, writes)
        assertEquals(0, reads)
        assertEquals(1, gate.snapshot().failures)
        assertEquals(before, spool.batch())
        assertNull(spool.status().lastSyncAt)

        permitted = true
        gate.run({ permitted }) { throw DiagnosticSyncFailure("DIAGNOSTIC_ACCESS_DENIED") }
        gate.pause()
        assertFalse(gate.refresh(false))
        assertEquals(GatewayDiagnosticRetryState.BLOCKED, gate.snapshot().state)
        repeat(4) { gate.run({ true }) { error("A privacy transition cannot clear authorization failure") } }
    }

    @Test fun cancellationAndRefreshInvalidateOldCompletionsWithoutSpendingAnotherFailure() = runTest {
        val gate = GatewayDiagnosticRetryGate { 0L }
        val entered = CompletableDeferred<Unit>()
        val cancelled = launch {
            gate.run({ true }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        assertFalse(gate.run({ true }) { error("Concurrent dispatch") })
        cancelled.cancelAndJoin()
        assertEquals(GatewayDiagnosticRetryState.READY, gate.snapshot().state)
        assertEquals(0, gate.snapshot().failures)

        val oldEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            gate.run({ true }) {
                oldEntered.complete(Unit)
                release.await()
                throw DiagnosticSyncFailure("DIAGNOSTIC_ACCESS_DENIED")
            }
        }
        oldEntered.await()
        assertTrue(gate.refresh(true))
        assertTrue(gate.run({ true }) { })
        release.complete(Unit)
        assertFalse(old.await())
        assertNull(gate.snapshot().error)
        assertEquals(0, gate.snapshot().failures)
        try {
            gate.run({ true }) { throw CancellationException("cancel") }
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(GatewayDiagnosticRetryState.READY, gate.snapshot().state)
    }

    @Test fun gateSerializesCompetingThreadsWithoutDispatchingDuplicateExchanges() {
        val gate = GatewayDiagnosticRetryGate { 0L }
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val returned = CountDownLatch(7)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val futures = (1..8).map {
                executor.submit<Boolean> {
                    start.await()
                    val result = runBlocking {
                        gate.run({ true }) {
                            calls.incrementAndGet()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }
                    if (!result) returned.countDown()
                    result
                }
            }
            start.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            release.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
