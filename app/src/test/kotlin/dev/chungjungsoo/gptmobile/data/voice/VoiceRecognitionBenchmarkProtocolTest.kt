package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionBenchmarkProtocolTest {
    @Test
    fun realtimePacingSeparatesPreparationDecodeAndPostInputFinalClocks() {
        val clock = Clock()
        val protocol = clock.protocol(audioFrames = 1_280, tailFrames = 640)
        clock.advanceMs(200)
        protocol.prepared()
        protocol.feed(640, { true }) {
            clock.advanceMs(10)
            protocol.partial("hello")
        }
        protocol.feed(640, { true }) { clock.advanceMs(10) }
        protocol.feed(640, { true }) {
            clock.advanceMs(10)
            protocol.speechEnded(40)
        }
        protocol.finish({ true }) {
            clock.advanceMs(5)
            protocol.final("hello")
        }
        val metrics = protocol.metrics()

        assertEquals(200.0, metrics.getValue("prepareMs"), 0.0)
        assertEquals(50.0, metrics.getValue("firstPartialMs"), 0.0)
        assertEquals(135.0, metrics.getValue("finalMs"), 0.0)
        assertEquals(55.0, metrics.getValue("finalAfterInputMs"), 0.0)
        assertEquals(55.0, metrics.getValue("finalAfterDecoderSpeechEndMs"), 0.0)
        assertEquals(1_280.0, metrics.getValue("decoderSpeechEndFrame"), 0.0)
        assertEquals(35.0, metrics.getValue("decodeBusyMs"), 0.0)
        assertEquals(5.0, metrics.getValue("flushMs"), 0.0)
        assertEquals(.4375, metrics.getValue("decodeRealTimeFactor"), 0.0)
        assertEquals(0.0, metrics.getValue("maxFeedLatenessMs"), 0.0)
        assertEquals(100_000_000L, clock.pauses.sum())
        assertTrue(protocol.measurementScope.contains("SYNTHETIC_FIXTURE"))
        assertTrue(protocol.measurementScope.contains("NOT_LIVE_MICROPHONE_OR_ACOUSTIC"))
    }

    @Test
    fun slowDecodeKeepsFixedSourceDeadlinesAndExposesLatenessInsteadOfAddingFreshSleeps() {
        val clock = Clock()
        val protocol = clock.protocol(audioFrames = 1_280)
        protocol.prepared()
        protocol.feed(640, { true }) { clock.advanceMs(80) }
        protocol.feed(640, { true }) { clock.advanceMs(10) }
        protocol.finish({ true }) { protocol.final("done") }

        assertEquals(40_000_000L, clock.pauses.sum())
        assertEquals(40.0, protocol.metrics().getValue("maxFeedLatenessMs"), 0.0)
        assertEquals(90.0, protocol.metrics().getValue("decodeBusyMs"), 0.0)
        assertEquals(130.0, protocol.metrics().getValue("inputAndFlushMs"), 0.0)
        assertEquals(50.0, protocol.metrics().getValue("finalAfterInputMs"), 0.0)
    }

    @Test
    fun cancellationDuringPacingStopsBeforeAnyNativeBatchIsAdmitted() {
        val clock = Clock()
        var active = true
        var decoded = false
        val protocol = VoiceRecognitionBenchmarkProtocol(640, tailSilenceFrames = 0, nowNanos = { clock.nanos }, pauseNanos = {
            clock.nanos += it
            active = false
        })
        protocol.prepared()

        assertThrows(CancellationException::class.java) { protocol.feed(640, { active }) { decoded = true } }
        assertFalse(decoded)
        assertFalse(protocol.hasAcceptedInput)
        assertEquals(10_000_000L, clock.nanos)
        assertThrows(IllegalStateException::class.java) { protocol.metrics() }
    }

    @Test
    fun unpacedThroughputNeverExportsRealtimeOrPostSpeechLatencyClaims() {
        val clock = Clock()
        val protocol = clock.protocol(640, mode = VoiceRecognitionBenchmarkInputMode.AS_FAST_AS_POSSIBLE)
        protocol.prepared()
        protocol.feed(640, { true }) {
            clock.advanceMs(5)
            protocol.partial("hello")
            protocol.speechEnded(0)
        }
        protocol.finish({ true }) {
            clock.advanceMs(7)
            protocol.final("hello")
        }
        val metrics = protocol.metrics()

        assertTrue(clock.pauses.isEmpty())
        assertEquals(0.0, metrics.getValue("realtimePacedInput"), 0.0)
        assertEquals(12.0, metrics.getValue("finalMs"), 0.0)
        assertFalse("finalAfterInputMs" in metrics)
        assertFalse("finalAfterDecoderSpeechEndMs" in metrics)
        assertFalse("maxFeedLatenessMs" in metrics)
        assertTrue(protocol.measurementScope.contains("THROUGHPUT_NOT_REALTIME"))
    }

    @Test
    fun prepareTimeCallbacksAndBlankFinalFlushCannotSupplyRecognitionEvidence() {
        val clock = Clock()
        val protocol = clock.protocol(640)
        protocol.partial("old stream")
        protocol.final("old stream")
        protocol.prepared()
        protocol.partial("still no input")
        protocol.final("still no input")
        protocol.feed(640, { true }) { protocol.partial(" ") }
        protocol.finish({ true }) { protocol.final("") }

        val metrics = protocol.metrics()
        assertFalse("firstPartialMs" in metrics)
        assertFalse("firstFinalMs" in metrics)
        assertFalse("finalMs" in metrics)
        assertEquals(0.0, metrics.getValue("finalCallbacks"), 0.0)
    }

    @Test
    fun earlyFinalIsReportedAsEarlyAndLateCallbacksCannotReviseTheCompletedReceipt() {
        val clock = Clock()
        val protocol = clock.protocol(1_280)
        protocol.prepared()
        protocol.feed(640, { true }) { protocol.final("short") }
        protocol.feed(640, { true }) { }
        protocol.finish({ true }) { }
        val completed = protocol.metrics()
        clock.advanceMs(500)
        protocol.final("late replacement")
        protocol.partial("late partial")
        protocol.speechEnded(0)

        assertEquals(-40.0, completed.getValue("finalAfterInputMs"), 0.0)
        assertEquals(completed, protocol.metrics())
        assertEquals(1.0, completed.getValue("finalCallbacks"), 0.0)
    }

    @Test
    fun impossibleSpeechEndsAndNonMonotonicClocksRemainUnqualified() {
        val clock = Clock()
        val protocol = clock.protocol(640)
        protocol.prepared()
        protocol.feed(640, { true }) {
            protocol.speechEnded(41)
            protocol.speechEnded(-1)
        }
        protocol.finish({ true }) { protocol.final("hello") }
        assertFalse("decoderSpeechEndFrame" in protocol.metrics())
        assertFalse("finalAfterDecoderSpeechEndMs" in protocol.metrics())

        val other = clock.protocol(640)
        other.prepared()
        clock.nanos--
        assertThrows(IllegalStateException::class.java) { other.feed(640, { true }) { } }
    }

    @Test
    fun captionEvidenceCountsDistinguishTokenStartsFromSuppliedWordDurations() {
        val clock = Clock()
        val protocol = clock.protocol(640)
        protocol.prepared()
        protocol.feed(640, { true }) { }
        val transcript = VoiceRecognitionTranscript.fromTokens("hello", listOf("\u2581hello"), listOf(.01), 0.0, 1, 1, true, 16_000, 640)
        protocol.finish({ true }) { protocol.final("hello", transcript) }

        assertEquals(1.0, protocol.metrics().getValue("timedRecognitionUnits"), 0.0)
        assertEquals(0.0, protocol.metrics().getValue("wordDurationUnits"), 0.0)
        assertEquals(0.0, protocol.metrics().getValue("recognitionTimingRejected"), 0.0)
    }

    @Test
    fun proposedEndpointCannotSupplyTerminalTranscriptTiming() {
        val clock = Clock()
        val protocol = clock.protocol(640)
        protocol.prepared()
        protocol.feed(640, { true }) { protocol.endpoint("candidate words") }
        protocol.finish({ true }) { }

        val metrics = protocol.metrics()
        assertEquals(40.0, metrics.getValue("firstEndpointMs"), 0.0)
        assertFalse("firstFinalMs" in metrics)
        assertFalse("finalMs" in metrics)
        assertEquals(0.0, metrics.getValue("finalCallbacks"), 0.0)
    }

    @Test
    fun incompleteOrOverlongInputCannotYieldACompletedTimingReceipt() {
        val clock = Clock()
        val protocol = clock.protocol(640)
        protocol.prepared()
        assertThrows(IllegalStateException::class.java) { protocol.finish({ true }) { } }
        assertThrows(IllegalArgumentException::class.java) { protocol.feed(641, { true }) { } }
        protocol.feed(640, { true }) { }
        protocol.finish({ true }) { }
        assertThrows(IllegalStateException::class.java) { protocol.feed(1, { true }) { } }
        assertThrows(IllegalStateException::class.java) { protocol.finish({ true }) { } }
    }

    private class Clock {
        var nanos = 0L
        val pauses = mutableListOf<Long>()
        fun advanceMs(value: Long) {
            nanos += value * 1_000_000
        }

        fun protocol(audioFrames: Long, tailFrames: Long = 0, mode: VoiceRecognitionBenchmarkInputMode = VoiceRecognitionBenchmarkInputMode.REALTIME_PACED) =
            VoiceRecognitionBenchmarkProtocol(audioFrames, tailSilenceFrames = tailFrames, mode = mode, nowNanos = { nanos }, pauseNanos = {
                pauses += it
                nanos += it
            })
    }
}
