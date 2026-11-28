package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeCaptionBufferTest {
    @Test fun trailingTimingReleasesOwnedAudioBeforeTheNextNativeRead() {
        val sdkSamples = floatArrayOf(.1f, .2f, .3f, .4f)
        val emitted = mutableListOf<FloatArray>()
        MediaPipeCaptionBuffer(16).use { buffer ->
            buffer.accept(sdkSamples, 0, emitted::add)
            assertTrue(emitted.isEmpty())
            sdkSamples.fill(.9f)
            buffer.accept(FloatArray(0), 4, emitted::add)
            assertArrayEquals(floatArrayOf(.1f, .2f, .3f, .4f), emitted.single(), 0f)
            assertEquals(4L, buffer.forwardedFrames)
            assertEquals(0, buffer.bufferedFrames)
            buffer.finish(emitted::add)
            assertEquals(1, emitted.size)
        }
    }

    @Test fun timingReleasesOnlyItsObservedPrefixAndRepeatedMetadataNeverReplaysPcm() {
        val emitted = mutableListOf<FloatArray>()
        MediaPipeCaptionBuffer(16).use { buffer ->
            buffer.accept(floatArrayOf(.1f, .2f, .3f, .4f), 2, emitted::add)
            assertArrayEquals(floatArrayOf(.1f, .2f), emitted.single(), 0f)
            assertEquals(2, buffer.bufferedFrames)
            buffer.accept(FloatArray(0), 2, emitted::add)
            assertEquals(1, emitted.size)
            buffer.finish(emitted::add)
            assertArrayEquals(floatArrayOf(.3f, .4f), emitted.last(), 0f)
            assertEquals(4L, buffer.forwardedFrames)
        }
    }

    @Test fun missingTimingHasBoundedRetentionAndPreservesEverySampleExactlyOnce() {
        val source = FloatArray(100) { it / 100f }
        val emitted = mutableListOf<Float>()
        var accepted = 0
        MediaPipeCaptionBuffer(17).use { buffer ->
            for (part in source.asList().chunked(13)) {
                buffer.accept(part.toFloatArray(), 0) { emitted.addAll(it.asList()) }
                accepted += part.size
                assertTrue(buffer.bufferedFrames <= 17)
                assertEquals(accepted, emitted.size + buffer.bufferedFrames)
            }
            assertTrue(emitted.isNotEmpty())
            buffer.finish { emitted.addAll(it.asList()) }
            assertArrayEquals(source, emitted.toFloatArray(), 0f)
            assertEquals(100L, buffer.forwardedFrames)
        }
    }

    @Test fun cancellationDiscardsPendingAudioWithoutCallingTheConsumer() {
        val buffer = MediaPipeCaptionBuffer(16)
        buffer.accept(floatArrayOf(.1f, .2f), 0) { error("Timing is still unavailable") }
        buffer.close()
        assertEquals(0, buffer.bufferedFrames)
        assertEquals(0L, buffer.forwardedFrames)
        assertThrows(IllegalStateException::class.java) { buffer.finish { error("Stopped audio must not drain") } }
        assertThrows(IllegalStateException::class.java) { buffer.accept(FloatArray(0), 2) {} }
    }
}
