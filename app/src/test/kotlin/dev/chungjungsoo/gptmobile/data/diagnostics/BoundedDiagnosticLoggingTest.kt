package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDiagnosticLoggingTest {
    @Test fun oversizedMessageRedactionRetainsOnlyCompleteBoundedLines() {
        val source = "errorCode=NETWORK_TIMEOUT\nAuthorization: Bearer private-canary\n" + "private-canary".repeat(100_000)
        val result = diagnosticMessageForStorage(source)
        assertTrue(result.contains("errorCode=NETWORK_TIMEOUT"))
        assertTrue(result.contains("truncatedAtSource=true"))
        assertFalse(result.contains("private-canary"))
        assertTrue(result.length < 8000)
    }

    @Test fun oversizedSingleLineIsOmittedWithoutPassingASecretFragmentToRedaction() {
        val source = "x".repeat(16 * 1024 - 8) + "Authorization: Bearer private-canary" + "x".repeat(100_000)
        val result = diagnosticMessageForStorage(source)
        assertTrue(result.contains("oversized diagnostic message omitted"))
        assertTrue(result.contains("truncatedAtSource=true"))
        assertFalse(result.contains("private-canary"))
        assertFalse(result.contains("Authorization"))
        assertTrue(result.length < 256)
    }

    @Test fun continuationPrefixesCannotExpandOneRecordWithoutABound() {
        val result = boundedDiagnosticRecord("2026-11-06T00:00:00Z E/Test: eventId=example · ", "example", "frame\n".repeat(10_000))
        assertTrue(result.length <= DiagnosticLogFiles.MAX_RECORD_BYTES)
        assertTrue(result.contains("parentEventId=example · sequence=1 · frame"))
        assertTrue(result.contains("truncatedAtSource=true"))
    }

    @Test fun shortRecordsPreserveRootAndContinuationIdentity() {
        assertEquals("root one\nparentEventId=event · sequence=1 · two", boundedDiagnosticRecord("root ", "event", "one\ntwo"))
    }

    @Test fun duplicateIdentityTrackingCannotGrowWithAnEntireLegacyHistory() {
        val window = BoundedDiagnosticIdentityWindow(16, 128)
        repeat(100_000) { assertTrue(window.add("event-$it")) }
        assertTrue(window.size <= 16)
        assertTrue(window.retainedCharacters <= 128)
        assertTrue(window.evictedRecords > 99_980)
        assertFalse(window.add("event-99999"))
        assertTrue(window.add("event-0"))
    }

    @Test fun coalescedSamplesDoNotRetainTheFullSizeOfEveryNoisyMessage() {
        val coalescer = DiagnosticCoalescer { 0L }
        repeat(128) { index ->
            val message = "OpenGLRenderer source=$index " + "x".repeat(7000)
            coalescer.accept("Android", message, "W")
            coalescer.accept("Android", message, "W")
        }
        val summaries = coalescer.drain()
        assertEquals(128, summaries.size)
        assertTrue(summaries.all { it.second.length < 650 && "coalescedSampleTruncated=true" in it.second })
    }
}
