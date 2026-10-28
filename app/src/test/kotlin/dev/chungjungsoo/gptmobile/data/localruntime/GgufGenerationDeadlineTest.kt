package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufGenerationDeadlineTest {
    @org.junit.Test fun receiptExportsTheExactResolvedThresholdsUsedByTheMonitor() {
        val deadline = GgufGenerationDeadline(1_000, 12_000, 90_000, 8_000, 0.5, 30_000)
        org.junit.Assert.assertEquals(
            NativeGenerationDeadlinePolicy(90_000, 12_000, 8_000, 0.5, 30_000),
            deadline.policy
        )
        org.junit.Assert.assertNull(deadline.expiration(12_999))
        org.junit.Assert.assertEquals("GGUF_NATIVE_STALL", deadline.expiration(13_000))
        org.junit.Assert.assertEquals("GGUF_OPERATION_DEADLINE", deadline.expiration(91_000))
    }

    @Test fun productivePrefillCanOutliveTheOldFirstTextCutoff() {
        val deadline = GgufGenerationDeadline(0, 75_000)
        deadline.observe(GgufNativeProgress(1, "PREFILL_BEGIN", 29, 0, 0), 0)
        deadline.observe(GgufNativeProgress(1, "PREFILL_BATCH_END", 29, 24, 0), 73_024)
        assertNull(deadline.expiration(75_001))
        assertEquals("GGUF_NATIVE_STALL", deadline.expiration(148_024))
    }

    @Test fun absoluteExpiryPreservesItsReasonDespiteUsefulProgress() {
        val deadline = GgufGenerationDeadline(0, 75_000)
        deadline.observe(GgufNativeProgress(1, "PREFILL_BATCH_END", 2000, 1800, 0), 599_999)
        assertEquals("GGUF_OPERATION_DEADLINE", deadline.expiration(600_000))
    }

    @Test fun sustainedLowThroughputAndDecodeStallsAreDifferent() {
        val slow = GgufGenerationDeadline(0, 75_000)
        slow.observe(GgufNativeProgress(1, "PREFILL_BEGIN", 2000, 0, 0), 0)
        slow.observe(GgufNativeProgress(1, "PREFILL_BATCH_END", 2000, 10, 0), 119_000)
        assertEquals("GGUF_PREFILL_THROUGHPUT_DEADLINE", slow.expiration(120_000))
        val decode = GgufGenerationDeadline(0, 75_000)
        decode.observe(GgufNativeProgress(1, "FIRST_BYTES", 29, 29, 1), 1000)
        assertNull(decode.expiration(45_999))
        assertEquals("GGUF_DECODE_STALL", decode.expiration(46_000))
    }

    @Test fun openClApiCallbacksShareABoundedInitializationWindow() {
        assertEquals(GgufLoadDeadline.group("INITIALIZING_BACKEND"), GgufLoadDeadline.group("OPENCL_clBuildProgram_BEGIN"))
        assertTrue(GgufLoadDeadline.expired("OPENCL_clBuildProgram_END_rc_0", 0, 20_000))
        assertEquals(180_000L, GgufLoadDeadline.ABSOLUTE_LOAD_MS)
    }

    @Test fun repeatedPreparationReceiptsCannotKeepAnIdleWorkerAlive() {
        val deadline = GgufGenerationDeadline(0, 75_000)
        deadline.observe(GgufNativeProgress(1, "RENDER_END"), 1000)
        deadline.observe(GgufNativeProgress(1, "RENDER_END"), 74_000)
        assertEquals("GGUF_NATIVE_STALL", deadline.expiration(76_000))
    }

    @Test fun repeatedPrefillBeginCannotResetTheThroughputWindow() {
        val deadline = GgufGenerationDeadline(0, 75_000)
        deadline.observe(GgufNativeProgress(1, "PREFILL_BEGIN", 2000, 0, 0), 0)
        deadline.observe(GgufNativeProgress(1, "PREFILL_BATCH_END", 2000, 10, 0), 119_000)
        deadline.observe(GgufNativeProgress(1, "PREFILL_BEGIN", 2000, 10, 0), 119_500)
        assertEquals("GGUF_PREFILL_THROUGHPUT_DEADLINE", deadline.expiration(120_000))
    }
}
