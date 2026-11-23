package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCpuCalibrationProtocolTest {
    @Test fun selectedRateAndAllFourCandidatesAreMeasuredWithoutChangingTheirVoiceContext() {
        var time = 0L
        var threads = 0
        val calls = mutableListOf<Pair<Int, Float>>()
        val measured = VoiceCpuCalibrationProtocol.measure(
            3,
            1.25f,
            { true },
            { 0 },
            { threads = it },
            { _, rate ->
                calls += threads to rate
                time += threads * 1_000_000L
            },
            { time }
        )
        assertEquals(listOf(4, 1, 2, 3), measured.keys.toList())
        assertEquals(16, calls.size)
        assertTrue(calls.all { it.second == 1.25f })
        assertTrue(measured.all { (candidate, samples) -> samples == List(3) { candidate.toLong() } })
        assertEquals(1, VoiceCpuCalibration.select(measured))
    }

    @Test fun orderRotatesEveryCandidateThroughEveryPositionAndThenReverses() {
        val orders = (0..7).map(VoiceCpuCalibrationProtocol::order)
        for (position in 0..3) assertEquals(setOf(1, 2, 3, 4), orders.take(4).map { it[position] }.toSet())
        assertEquals(orders[0].reversed(), orders[4])
        assertEquals(setOf(1, 2, 3, 4), VoiceCpuCalibrationProtocol.order(Int.MIN_VALUE).toSet())
    }

    @Test fun heatingOnTheLastTrialStillInvalidatesTheWholeCalibration() {
        var calls = 0
        val failure = assertThrows(VoiceCalibrationInterrupted::class.java) {
            VoiceCpuCalibrationProtocol.measure(0, 1f, { true }, { if (calls == 16) 2 else 0 }, {}, { _, _ -> calls++ }, { calls * 1_000_000L })
        }
        assertEquals(VoiceCalibrationStop.THERMAL, failure.reason)
        assertEquals(16, calls)
    }

    @Test fun cancellationAfterWarmupDoesNotRunOrCommitTrials() {
        var calls = 0
        val failure = assertThrows(VoiceCalibrationInterrupted::class.java) {
            VoiceCpuCalibrationProtocol.measure(0, 1f, { calls == 0 }, { 0 }, {}, { _, _ -> calls++ }, { 0 })
        }
        assertEquals(VoiceCalibrationStop.CANCELLED, failure.reason)
        assertEquals(1, calls)
    }

    @Test fun missingTemperatureCannotQualifyASelection() {
        val failure = assertThrows(VoiceCalibrationInterrupted::class.java) {
            VoiceCpuCalibrationProtocol.measure(0, 1f, { true }, { null }, { error("must not open") }, { _, _ -> error("must not synthesize") }, { 0 })
        }
        assertEquals(VoiceCalibrationStop.THERMAL_UNAVAILABLE, failure.reason)
    }

    @Test fun calibrationIdentityChangesWithVoiceRateAndEngineRevision() {
        val original = VoiceCpuCalibrationProtocol.key("runtime1", KokoroVoice.HEART, 1.25f)
        assertNotEquals(original, VoiceCpuCalibrationProtocol.key("runtime1", KokoroVoice.ADAM, 1.25f))
        assertNotEquals(original, VoiceCpuCalibrationProtocol.key("runtime1", KokoroVoice.HEART, 1f))
        assertNotEquals(original, VoiceCpuCalibrationProtocol.key("runtime2", KokoroVoice.HEART, 1.25f))
        assertTrue(original.startsWith("threads:v3:"))
    }
}
