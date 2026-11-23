package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamDeliveryCadenceTest {
    @Test fun aDeliveryIsVisibleWhileItsFirstStreamRemainsOpen() {
        val cadence = VoiceStreamDeliveryCadence()
        assertFalse(cadence.started)
        assertNull(cadence.reserveCostMs())
        cadence.begin()
        assertTrue(cadence.started)
        assertNull(cadence.reserveCostMs())
        cadence.observe(450, 2_400)
        assertEquals(450L, cadence.reserveCostMs())
        cadence.observe(800, 2_400)
        assertEquals(800L, cadence.reserveCostMs())
    }

    @Test fun instantaneousSubdivisionsDoNotEvictTheActiveSlowDelivery() {
        val cadence = VoiceStreamDeliveryCadence()
        cadence.begin()
        cadence.observe(1_200, 2_400)
        repeat(64) { cadence.observe(0, 2_400) }
        assertEquals(1_200L, cadence.reserveCostMs())
        cadence.end(0)
        assertEquals(1_200L, cadence.reserveCostMs())
    }

    @Test fun fullyTrimmedCallbacksAccumulateUntilRetainedPcmArrives() {
        val cadence = VoiceStreamDeliveryCadence()
        cadence.begin()
        cadence.observe(350, 0)
        cadence.observe(450, 0)
        assertEquals(800L, cadence.reserveCostMs())
        cadence.observe(250, 2_400)
        assertEquals(1_050L, cadence.reserveCostMs())
        cadence.observe(0, 2_400)
        assertEquals(1_050L, cadence.reserveCostMs())
    }

    @Test fun trimmedTailAndRetirementCarryAcrossTheNextStreamBoundary() {
        val cadence = VoiceStreamDeliveryCadence()
        cadence.begin()
        cadence.observe(100, 2_400)
        cadence.observe(350, 0)
        cadence.observe(450, 0)
        cadence.end(200)
        assertEquals(1_000L, cadence.reserveCostMs())
        cadence.begin()
        cadence.observe(250, 2_400)
        assertEquals(1_250L, cadence.reserveCostMs())
        cadence.end(0)
        assertEquals(1_250L, cadence.reserveCostMs())
    }

    @Test fun eightActualAudioProducingStreamsAgeTheCompletedSlowTail() {
        val cadence = VoiceStreamDeliveryCadence()
        cadence.begin()
        cadence.observe(1_200, 2_400)
        cadence.end(0)
        repeat(7) {
            cadence.begin()
            repeat(40) { cadence.observe(0, 2_400) }
            cadence.end(0)
            assertEquals(1_200L, cadence.reserveCostMs())
        }
        cadence.begin()
        cadence.observe(0, 2_400)
        assertEquals(1_200L, cadence.reserveCostMs())
        cadence.end(0)
        assertEquals(0L, cadence.reserveCostMs())
    }

    @Test fun fullyTrimmedStreamsCannotManufactureFastAudioDelivery() {
        val cadence = VoiceStreamDeliveryCadence()
        cadence.begin()
        cadence.observe(1_200, 2_400)
        cadence.end(0)
        repeat(12) {
            cadence.begin()
            cadence.observe(100, 0)
            cadence.end(0)
        }
        cadence.begin()
        cadence.observe(300, 2_400)
        assertEquals(1_500L, cadence.reserveCostMs())
    }

    @Test fun producerTimeSaturatesWithoutWrappingOrLosingTheGap() {
        val cadence = VoiceStreamDeliveryCadence()
        cadence.begin()
        cadence.observe(Long.MAX_VALUE - 5, 0)
        cadence.observe(20, 0)
        cadence.observe(20, 2_400)
        assertEquals(Long.MAX_VALUE, cadence.reserveCostMs())
        cadence.end(0)
        assertEquals(Long.MAX_VALUE, cadence.reserveCostMs())
    }

    @Test fun invalidLifecycleAndSamplesCannotMutateValidTiming() {
        val cadence = VoiceStreamDeliveryCadence()
        assertThrows(IllegalStateException::class.java) { cadence.observe(10, 1) }
        assertThrows(IllegalStateException::class.java) { cadence.end(0) }
        cadence.begin()
        cadence.observe(20, 2_400)
        assertThrows(IllegalStateException::class.java) { cadence.begin() }
        assertThrows(IllegalArgumentException::class.java) { cadence.observe(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { cadence.observe(1, -1) }
        assertEquals(20L, cadence.reserveCostMs())
        cadence.end(0)
        assertThrows(IllegalStateException::class.java) { cadence.observe(10, 1) }
        assertEquals(20L, cadence.reserveCostMs())
    }
}
