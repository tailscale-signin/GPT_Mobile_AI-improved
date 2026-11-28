package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketCaptionAlignerTest {
    @Test
    fun analysisTailIsSeparateFromOriginalPcmAndReturnedTimesMustFitOriginalAudio() {
        val original = FloatArray(3_001) { .1f }
        var released = false
        var freed = 0
        val native = object : PocketCaptionNativeApi {
            override fun load(directory: File) = 7
            override fun transcribe(model: Int, samples: FloatArray, sampleRate: Int): PocketCaptionTranscript {
                assertEquals(7, model)
                assertEquals(24_000, sampleRate)
                assertEquals(0, samples.size % PocketCaptionAligner.ANALYSIS_BLOCK_FRAMES)
                assertTrue(samples.size > original.size)
                assertArrayEquals(original, samples.copyOf(original.size), 0f)
                assertTrue(samples.drop(original.size).all { it == 0f })
                return PocketCaptionTranscript(listOf(PocketCaptionWord("hello", .01, .1)), true)
            }
            override fun free(model: Int) {
                freed++
            }
        }
        val engine = PocketCaptionAligner.open(PocketCaptionModelStore.ModelLease(File("unused")) { released = true }, {}, {}, native)
        assertEquals(PocketCaptionStatus.ALIGNED, engine.align("hello", original) {}.status)
        assertTrue(original.all { it == .1f })
        assertFalse(released)
        engine.close()
        engine.close()
        assertTrue(released)
        assertEquals(1, freed)
    }

    @Test
    fun cancellationAfterNativeReturnSuppressesTimingsAndRetiresOnlyOnOwnerClose() {
        val cancelled = AtomicBoolean()
        var released = false
        var freed = false
        val native = object : PocketCaptionNativeApi {
            override fun load(directory: File) = 3
            override fun transcribe(model: Int, samples: FloatArray, sampleRate: Int): PocketCaptionTranscript {
                cancelled.set(true)
                return PocketCaptionTranscript(listOf(PocketCaptionWord("hello", 0.0, .1)), true)
            }
            override fun free(model: Int) {
                freed = true
            }
        }
        val checkActive = { if (cancelled.get()) throw CancellationException("test cancellation") }
        val engine = PocketCaptionAligner.open(PocketCaptionModelStore.ModelLease(File("unused")) { released = true }, checkActive, {}, native)
        assertThrows(CancellationException::class.java) { engine.align("hello", FloatArray(4_000), checkActive) }
        assertFalse(released)
        assertFalse(freed)
        engine.close()
        assertTrue(released)
        assertTrue(freed)
    }

    @Test
    fun uncertainNativeRetirementRetainsLeaseAndIsNotRetried() {
        var released = false
        var freed = 0
        var quarantined: Any? = null
        val native = object : PocketCaptionNativeApi {
            override fun load(directory: File) = 4
            override fun transcribe(model: Int, samples: FloatArray, sampleRate: Int) = PocketCaptionTranscript(emptyList(), false)
            override fun free(model: Int) {
                freed++
                throw IllegalStateException("unconfirmed native free")
            }
        }
        val engine = PocketCaptionAligner.open(PocketCaptionModelStore.ModelLease(File("unused")) { released = true }, {}, { quarantined = it }, native)
        assertThrows(PocketCaptionCleanupFailure::class.java) { engine.close() }
        assertThrows(PocketCaptionCleanupFailure::class.java) { engine.close() }
        assertTrue(quarantined === engine)
        assertFalse(released)
        assertEquals(1, freed)
    }
}
