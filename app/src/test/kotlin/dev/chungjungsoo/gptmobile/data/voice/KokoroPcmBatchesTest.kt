package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroPcmBatchesTest {
    @Test fun callbackPcmIsBoundedByDurationWithoutLosingOrRepeatingFrames() {
        for (sampleRate in listOf(8_000, 24_000, 48_000)) {
            val original = FloatArray(sampleRate * 2 + 7) { it.toFloat() }
            val emitted = mutableListOf<FloatArray>()
            assertTrue(
                KokoroPcmBatches.emit(original, sampleRate) {
                    emitted += it
                    true
                }
            )
            assertTrue(emitted.all { it.isNotEmpty() && it.size <= sampleRate / 10 })
            assertArrayEquals(original, emitted.flatMap { it.asIterable() }.toFloatArray(), 0f)
            assertEquals(6_000, KokoroPcmBatches.QUEUE_LIMIT_MS)
        }
    }

    @Test fun cancellationStopsProducingBatchesImmediately() {
        var batches = 0
        assertFalse(KokoroPcmBatches.emit(FloatArray(24_000 * 30), 24_000) { ++batches < 2 })
        assertEquals(2, batches)
    }

    @Test fun emptyCallbackDoesNotCreateAPhantomAudioBatch() {
        assertTrue(KokoroPcmBatches.emit(floatArrayOf(), 24_000) { error("empty audio must not be queued") })
    }
}
