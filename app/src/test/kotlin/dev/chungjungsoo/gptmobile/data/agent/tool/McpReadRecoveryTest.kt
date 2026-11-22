package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.MetadataResponseObservation
import dev.chungjungsoo.gptmobile.data.network.PrivateMetadataDenied
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpReadRecoveryTest {
    @Test fun wrappedCancellationDoesNotRetryOrInventInterruptedReadEvidence() = runTest {
        val cancelled = CancellationException("Owner cancelled the request")
        for (failure in listOf(
            IOException("connection reset", cancelled),
            IllegalStateException("SDK wrapper", IOException("unexpected end of stream", cancelled))
        )) {
            val evidence = MetadataResponseObservation()
            var attempts = 0
            var pauses = 0
            val observed = withContext(evidence) {
                runCatching {
                    withMcpReadRecovery("tools/list", pause = { pauses++ }) {
                        attempts++
                        throw failure
                    }
                }.exceptionOrNull()
            }
            assertSame(cancelled, observed)
            assertEquals(1, attempts)
            assertEquals(0, pauses)
            assertFalse(evidence.hasInterruptedRead)
            assertNull(evidence.observedAtEpochMs)
            assertTrue(cancelled.suppressed.isEmpty())
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        }
    }

    @Test fun wrappedRetryCancellationRetainsEarlierReadAndResponseEvidence() = runTest {
        val cancelled = CancellationException("Owner cancelled the retry")
        val evidence = MetadataResponseObservation()
        evidence.responded()
        val responseAt = evidence.observedAtEpochMs
        var attempts = 0
        val observed = withContext(evidence) {
            runCatching {
                withMcpReadRecovery("tools/list", pause = {}) {
                    if (++attempts == 1) throw EOFException("Earlier response was interrupted")
                    throw IOException("socket closed", cancelled)
                }
            }.exceptionOrNull()
        }
        assertSame(cancelled, observed)
        assertEquals(2, attempts)
        assertTrue(evidence.hasInterruptedRead)
        assertEquals(responseAt, evidence.observedAtEpochMs)
        assertTrue(cancelled.suppressed.isEmpty())
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    @Test fun genericTlsReadInterruptionIsNotAHandshakeAdmissionFailure() = runTest {
        val evidence = MetadataResponseObservation()
        withContext(evidence) {
            recordMcpInterruptedRead(javax.net.ssl.SSLException("Secure stream ended", EOFException("Response was truncated")))
        }
        assertTrue(evidence.hasInterruptedRead)
        assertNull(evidence.observedAtEpochMs)
    }

    @Test fun concurrentDiscoveryScopesDoNotBorrowInterruptedReadEvidence() = runTest {
        val interrupted = MetadataResponseObservation()
        val untouched = MetadataResponseObservation()
        val enteredRetry = CompletableDeferred<Unit>()
        val resumeRetry = CompletableDeferred<Unit>()
        var attempts = 0
        val pending = async {
            runCatching {
                withContext(interrupted) {
                    withMcpReadRecovery("tools/list", pause = {
                        enteredRetry.complete(Unit)
                        resumeRetry.await()
                    }) {
                        if (++attempts == 1) throw IllegalStateException("SDK wrapper", EOFException("private transport details"))
                        throw PrivateMetadataDenied()
                    }
                }
            }.exceptionOrNull()
        }
        enteredRetry.await()
        val other = withContext(untouched) {
            runCatching {
                withMcpReadRecovery("tools/list", pause = { error("A local denial cannot retry") }) {
                    throw PrivateMetadataDenied()
                }
            }.exceptionOrNull()
        }
        assertTrue(other?.isLocalPrivacyPolicyRejection() == true)
        assertTrue(interrupted.hasInterruptedRead)
        assertFalse(untouched.hasInterruptedRead)
        resumeRetry.complete(Unit)
        assertTrue(pending.await()?.isLocalPrivacyPolicyRejection() == true)
        assertEquals(2, attempts)
        assertTrue(interrupted.hasInterruptedRead)
        assertFalse(untouched.hasInterruptedRead)
        assertFalse(MetadataResponseObservation().hasInterruptedRead)
    }

    @Test fun knownPreDispatchFailuresDoNotInventAnUncertainRead() = runTest {
        for ((first, expectedAttempts) in listOf(
            UnknownHostException("DNS unavailable") to 2,
            IllegalStateException("Not connected") to 2,
            java.net.ConnectException("Connection refused") to 1,
            javax.net.ssl.SSLHandshakeException("Untrusted peer").apply { initCause(EOFException()) } to 1,
            javax.net.ssl.SSLPeerUnverifiedException("Peer not verified") to 1,
            javax.net.ssl.SSLException("Rejected certificate", java.security.cert.CertificateException()) to 1,
            PrivateMetadataDenied() to 1
        )) {
            val evidence = MetadataResponseObservation()
            var attempts = 0
            withContext(evidence) {
                recordMcpInterruptedRead(first)
                runCatching {
                    withMcpReadRecovery("tools/list", pause = {}) {
                        if (++attempts == 1) throw first
                        throw PrivateMetadataDenied()
                    }
                }
            }
            assertEquals(first.javaClass.simpleName, expectedAttempts, attempts)
            assertFalse(first.javaClass.simpleName, evidence.hasInterruptedRead)
        }
    }

    @Test fun anInterruptedTlsReadCannotBecomeProofThatNothingWasDispatched() = runTest {
        for (failure in listOf(
            javax.net.ssl.SSLException("Connection reset during response", EOFException()),
            IllegalStateException("SDK wrapper", javax.net.ssl.SSLException("Connection reset during response", EOFException()))
        )) {
            val evidence = MetadataResponseObservation()
            withContext(evidence) {
                assertTrue(failure.isMcpInterruptedRead())
                // The legacy session reconnect path records this acquisition before
                // another local gate can reject its retry. A general TLS read
                // failure does not establish that the earlier exchange never began.
                recordMcpInterruptedRead(failure)
                recordMcpInterruptedRead(PrivateMetadataDenied())
            }
            assertTrue(evidence.hasInterruptedRead)
            assertEquals(null, evidence.observedAtEpochMs)
            assertFalse(MetadataResponseObservation().hasInterruptedRead)
        }
    }

    @Test fun cancellingAnInterruptedReadRetryRetainsCancellationAndNoSharedHistory() = runTest {
        val evidence = MetadataResponseObservation()
        val cancelled = CancellationException("Owner cancelled retry")
        var attempts = 0
        val failure = withContext(evidence) {
            runCatching {
                withMcpReadRecovery("tools/list", pause = { throw cancelled }) {
                    attempts++
                    throw EOFException("private transport details")
                }
            }.exceptionOrNull()
        }
        assertSame(cancelled, failure)
        assertTrue(evidence.hasInterruptedRead)
        assertEquals(1, attempts)
        assertTrue(cancelled.suppressed.isEmpty())
        assertFalse(MetadataResponseObservation().hasInterruptedRead)
    }

    @Test fun firstAttemptPolicyDenialDoesNotCreateReadRetryEvidence() = runTest {
        for (denied in listOf(PrivateMetadataDenied(), UnverifiedServiceTransport(), IOException("connection reset", PrivateMetadataDenied()))) {
            val observation = MetadataResponseObservation()
            var attempts = 0
            val observed = withContext(observation) {
                runCatching {
                    withMcpReadRecovery("server/discover", pause = { error("A policy denial must not pause for retry") }) {
                        attempts++
                        throw denied
                    }
                }.exceptionOrNull()
            }
            assertTrue(observed === denied)
            assertEquals(1, attempts)
            assertFalse(observation.hasInterruptedRead)
            assertNull(observation.observedAtEpochMs)
        }
    }

    @Test fun cancellationOfAReadRetryRemainsCancellation() = runTest {
        val cancelled = CancellationException("cancelled while retrying")
        var attempts = 0
        val observed = runCatching {
            withMcpReadRecovery("server/discover", pause = {}) {
                attempts++
                throw if (attempts == 1) EOFException("first response was interrupted") else cancelled
            }
        }.exceptionOrNull()
        assertTrue(observed === cancelled)
        assertEquals(2, attempts)
    }

    @Test fun wrappedReadFailureRemainsObservedWhenItsRetryIsBlockedLocally() = runTest {
        val observation = MetadataResponseObservation()
        val first = IllegalStateException("SDK wrapper", EOFException("response interrupted"))
        val denied = PrivateMetadataDenied()
        var attempts = 0
        val observed = withContext(observation) {
            runCatching {
                withMcpReadRecovery("tools/list", pause = {}) {
                    throw if (++attempts == 1) first else denied
                }
            }.exceptionOrNull()
        }
        assertSame(denied, observed)
        assertSame(first, denied.suppressed.single())
        assertEquals(2, attempts)
        assertTrue(observation.hasInterruptedRead)
        assertNull(observation.observedAtEpochMs)
    }

    @Test fun localDenialsDisconnectedSdkStateAndCancellationDoNotInventInterruptedReads() = runTest {
        for (failure in listOf(PrivateMetadataDenied(), IOException("SDK wrapper", PrivateMetadataDenied()), IllegalStateException("Not connected"), CancellationException("owner cancelled"))) {
            val observation = MetadataResponseObservation()
            withContext(observation) {
                runCatching { withMcpReadRecovery("tools/list", pause = {}) { throw failure } }
            }
            assertFalse(observation.hasInterruptedRead)
            assertNull(observation.observedAtEpochMs)
        }
    }

    @Test fun anInterruptedLaterReadCannotEraseAnEarlierResponseObservation() = runTest {
        val observation = MetadataResponseObservation()
        observation.responded()
        val responseAt = observation.observedAtEpochMs
        var attempts = 0
        withContext(observation) {
            runCatching {
                withMcpReadRecovery("tools/list", pause = {}) {
                    throw if (++attempts == 1) EOFException("response interrupted") else PrivateMetadataDenied()
                }
            }
        }
        assertTrue(observation.hasInterruptedRead)
        assertEquals(responseAt, observation.observedAtEpochMs)
    }

    @Test fun aFailedSafeReadRetryRetainsItsOriginalTransportEvidence() = runTest {
        val first = UnknownHostException("first attempt")
        val second = EOFException("second attempt")
        var attempts = 0
        val observed = runCatching {
            withMcpReadRecovery("server/discover", pause = {}) {
                attempts++
                throw if (attempts == 1) first else second
            }
        }.exceptionOrNull()
        assertTrue(observed === second)
        assertEquals(listOf(first), second.suppressed.toList())
        assertEquals(2, attempts)
    }

    @Test fun incompleteCatalogReadRetriesOnceAndRetainsAlreadyCompletedPages() = runTest {
        val previousPages = mutableListOf("first page")
        var calls = 0
        val nextPage = withMcpReadRecovery("tools/list", pause = { }) {
            calls++
            if (calls == 1) throw McpIncompleteResponseException("tools/list", 50)
            "second page"
        }
        previousPages += nextPage
        assertEquals(listOf("first page", "second page"), previousPages)
        assertEquals(2, calls)
    }

    @Test fun dnsRecoveryIsBoundedAndDoesNotReplayMutatingCalls() = runTest {
        for (method in listOf("tools/list", "tools/call", "tasks/update", "tasks/cancel")) {
            var calls = 0
            val failure = runCatching {
                withMcpReadRecovery(method, pause = { }) {
                    calls++
                    throw UnknownHostException("transient")
                }
            }.exceptionOrNull()
            assertTrue(failure is UnknownHostException)
            assertEquals(if (method == "tools/list") 2 else 1, calls)
        }
    }

    @Test fun authorizationProtocolAndCancellationAreNotRetried() = runTest {
        for (expected in listOf(ModernMcpError(401, null, "Denied"), ModernMcpError(200, -32602, "Invalid arguments"), CancellationException("backgrounded"))) {
            var calls = 0
            val failure = runCatching {
                withMcpReadRecovery("tools/list", pause = { }) {
                    calls++
                    throw expected
                }
            }.exceptionOrNull()
            assertTrue(expected === failure)
            assertEquals(1, calls)
        }
        var writes = 0
        runCatching {
            withMcpReadRecovery("tools/call", pause = { }) {
                writes++
                throw EOFException()
            }
        }
        assertEquals(1, writes)
    }

    @Test fun plainEofReadRecoveryDoesNotTurnAnSslWrappedEofIntoAnAutomaticRetry() = runTest {
        val tls = javax.net.ssl.SSLException("Secure stream ended", EOFException("unexpected end of stream"))
        for ((expected, expectedCalls) in listOf(EOFException("unexpected end of stream") to 2, tls to 1, java.io.IOException("SDK wrapper", tls) to 1)) {
            var calls = 0
            val observed = runCatching {
                withMcpReadRecovery("tools/list", pause = { }) {
                    calls++
                    throw expected
                }
            }.exceptionOrNull()
            assertTrue(expected === observed)
            assertEquals(expectedCalls, calls)
        }
    }

    @Test fun requestDeadlineIsTransportFailureWhileParentDeadlineRemainsCancellation() = runTest {
        val deadline = runCatching { withMcpRequestDeadline(10, "tools/call") { delay(20) } }.exceptionOrNull()
        assertTrue(deadline is McpRequestTimeoutException)
        assertTrue(deadline?.message.orEmpty().contains("UNKNOWN_OUTCOME"))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val parent = runCatching { withTimeout(5) { withMcpRequestDeadline(10, "tools/call") { delay(20) } } }.exceptionOrNull()
        assertTrue(parent is CancellationException)
    }
}
