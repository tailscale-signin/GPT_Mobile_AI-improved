package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAttemptEvidenceTest {
    @Test fun redirectAndReconnectCannotBorrowPreviousExchangeBodyOrNegotiatedProtocol() {
        val trace = HttpAttemptEvidenceCollector()
        trace.phase(HttpAttemptPhase.CONNECTING)
        trace.connection("TLSv1.3", "h2")
        trace.phase(HttpAttemptPhase.REQUEST_BODY_COMPLETE)
        assertTrue(trace.snapshot().requestBodyCompleted)
        trace.phase(HttpAttemptPhase.REQUEST_HEADERS)
        assertFalse(trace.snapshot().requestBodyCompleted)
        trace.phase(HttpAttemptPhase.REQUEST_BODY_COMPLETE)
        trace.phase(HttpAttemptPhase.CONNECTING)
        trace.phase(HttpAttemptPhase.TLS_HANDSHAKE)
        val failedHandshake = trace.snapshot()
        assertFalse(failedHandshake.requestBodyCompleted)
        assertNull(failedHandshake.connectionReused)
        assertNull(failedHandshake.tlsVersion)
        assertNull(failedHandshake.httpProtocol)
        trace.connection("TLSv1.2", "http/1.1")
        assertEquals(false, trace.snapshot().connectionReused)
        trace.phase(HttpAttemptPhase.REQUEST_BODY_COMPLETE)
        trace.connection("TLSv1.3", "h2")
        assertEquals(true, trace.snapshot().connectionReused)
        assertFalse(trace.snapshot().requestBodyCompleted)
    }

    @Test fun reusedConnectionRetainsBodyCompletionWithoutClaimingAResponseOrSharingWithTheNextAttempt() {
        val first = HttpAttemptEvidenceCollector()
        first.phase(HttpAttemptPhase.CALL_STARTED)
        first.connection("TLSv1.3", "h2")
        first.phase(HttpAttemptPhase.REQUEST_BODY)
        first.phase(HttpAttemptPhase.REQUEST_BODY_COMPLETE)
        assertEquals(true, first.snapshot().connectionReused)
        assertEquals(HttpAttemptPhase.REQUEST_BODY_COMPLETE, first.snapshot().phase)
        assertTrue(first.snapshot().requestBodyCompleted)
        assertEquals("TLSv1.3", first.snapshot().tlsVersion)
        val next = HttpAttemptEvidenceCollector().snapshot()
        assertFalse(next.requestBodyCompleted)
        assertNull(next.connectionReused)
        assertEquals(HttpAttemptPhase.NOT_OBSERVED, next.phase)
    }

    @Test fun newConnectionAndUnknownProtocolNeverBecomeReusedOrPrivateDiagnosticValues() {
        val trace = HttpAttemptEvidenceCollector()
        trace.phase(HttpAttemptPhase.CONNECTING)
        trace.phase(HttpAttemptPhase.TLS_HANDSHAKE)
        assertNull(trace.snapshot().connectionReused)
        trace.connection("private certificate", "secret protocol")
        assertEquals(false, trace.snapshot().connectionReused)
        assertNull(trace.snapshot().tlsVersion)
        assertNull(trace.snapshot().httpProtocol)
        trace.phase(HttpAttemptPhase.RESPONSE_HEADERS)
        assertEquals(HttpAttemptPhase.RESPONSE_HEADERS, trace.snapshot().phase)
    }
}
