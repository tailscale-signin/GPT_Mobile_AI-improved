package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionBenchmarkFixtureTest {
    @Test
    fun pacedFixturesIgnorePreparationStaleAndForeignTextAndScoreOnlyTheTerminalRevision() {
        val clock = Clock()
        val decoder = Decoder(clock).apply {
            onStart = { emit(LocalSpeechEvent.Final("preparation output", transcript("preparation output", 99, true))) }
            onAccept = {
                if (frames == 640L) {
                    emit(LocalSpeechEvent.Partial(expected, transcript(expected, 4)))
                    emit(LocalSpeechEvent.Partial("stale", transcript("stale", 3)))
                    emit(LocalSpeechEvent.Final("foreign", transcript("foreign", 50).copy(streamId = stream + 99)))
                    emit(LocalSpeechEvent.Final(expected, transcript(expected, 5)))
                }
            }
            // Native blank flushes preserve useful admitted input without fabricating new timings.
            onFinish = { emit(LocalSpeechEvent.Final("", transcript("", 6, true))) }
        }
        val rows = run(decoder, clock)

        assertEquals(2, rows.size)
        assertEquals(listOf("ready", "weather"), rows.map { it.fixture })
        for (row in rows) {
            assertEquals(VoiceBenchStatus.MEASURED, row.status)
            assertEquals(0.0, row.metrics.getValue("wordErrors"), 0.0)
            assertEquals(1.0, row.metrics.getValue("finalCallbacks"), 0.0)
            assertTrue(row.metrics.getValue("firstEndpointMs") < row.metrics.getValue("finalMs"))
            assertTrue(row.metrics.getValue("finalAfterInputMs") >= 1_000.0)
            assertEquals(voiceBenchRecordingFrames.getValue(row.fixture).toDouble(), row.metrics.getValue("inputAudioFrames"), 0.0)
            assertEquals(0.0, row.metrics.getValue("wordDurationUnits"), 0.0)
        }
        assertEquals("en-US", decoder.language)
        assertTrue(decoder.vocabulary.isEmpty())
        assertEquals(2, decoder.releases)
        assertFalse(decoder.closed)
    }

    @Test
    fun endpointsWithoutATerminalFlushCannotBecomeFinalAccuracyOrLatencyEvidence() {
        val clock = Clock()
        val decoder = Decoder(clock).apply {
            onAccept = { if (frames == 640L) emit(LocalSpeechEvent.Final(expected, transcript(expected, 1))) }
        }
        for (row in run(decoder, clock)) {
            assertEquals(VoiceBenchStatus.FAILED, row.status)
            assertEquals("NO_FINAL_TRANSCRIPT", row.reason)
            assertTrue("firstEndpointMs" in row.metrics)
            assertFalse("finalMs" in row.metrics)
            assertEquals(0.0, row.metrics.getValue("finalCallbacks"), 0.0)
        }
    }

    @Test
    fun declaredFinalsOnPartialCallbacksAndFutureInputFramesCannotQualify() {
        for (futureFrames in listOf(false, true)) {
            val clock = Clock()
            val decoder = Decoder(clock).apply {
                onAccept = {
                    val supplied = transcript(expected, 1, !futureFrames).let {
                        if (futureFrames) it.copy(audioFrames = frames + 1) else it
                    }
                    emit(LocalSpeechEvent.Partial(expected, supplied))
                }
            }
            for (row in run(decoder, clock)) {
                assertEquals(VoiceBenchStatus.FAILED, row.status)
                assertEquals(if (futureFrames) "RECOGNITION_TRANSCRIPT_INPUT_MISMATCH" else "RECOGNITION_TRANSCRIPT_EVENT_MISMATCH", row.reason)
                assertFalse("wordErrorRate" in row.metrics)
                assertFalse("finalMs" in row.metrics)
            }
            assertEquals(2, decoder.releases)
        }
    }

    @Test
    fun warmupReusesThePreparedDecoderButDoesNotDuplicateTheScoredFixturesOrWordEnds() {
        val clock = Clock()
        val decoder = Decoder(clock, warmup = true).apply {
            onFinish = {
                val words = expected.trimEnd('.').split(' ')
                val timing = VoiceRecognitionTranscript.fromTokens(
                    expected,
                    words.mapIndexed { index, word -> if (index == words.lastIndex) "$word." else word },
                    words.indices.map { it / 10.0 },
                    0.0,
                    stream,
                    1,
                    true,
                    16_000,
                    frames
                )
                emit(LocalSpeechEvent.Final(expected, timing))
            }
        }
        val snapshots = mutableListOf<List<VoiceBenchObservation>>()
        val rows = run(decoder, clock, warmup = true, checkpoint = snapshots::add)

        assertEquals(listOf("warmup/ready", "ready", "weather"), rows.map { it.fixture })
        assertEquals(listOf("true", "false", "false"), rows.map { it.metadata["recognitionWarmup"] })
        assertEquals(listOf(1, 2, 3), snapshots.map { it.size })
        assertTrue(rows.all { it.status == VoiceBenchStatus.MEASURED && it.metrics.getValue("timedRecognitionUnits") > 0 && it.metrics.getValue("wordDurationUnits") == 0.0 })
        assertEquals(3, decoder.starts)
        assertEquals(3, decoder.releases)
        assertFalse(decoder.closed)
    }

    @Test
    fun aHotFinalThermalObservationInterruptsTheStageAndLeavesLaterFixturesUnmeasured() {
        val clock = Clock()
        var thermal = 0
        val decoder = Decoder(clock).apply {
            onFinish = {
                emit(LocalSpeechEvent.Final(expected, transcript(expected, 1, true)))
                thermal = 2
            }
        }
        val rows = run(decoder, clock, thermal = { thermal })

        assertEquals(1, rows.size)
        assertEquals(VoiceBenchStatus.INTERRUPTED, rows.single().status)
        assertEquals("THERMAL_LIMIT", rows.single().reason)
        assertEquals(2.0, rows.single().metrics.getValue("maximumObservedThermalStatus"), 0.0)
        assertFalse("wordErrorRate" in rows.single().metrics)
        assertEquals(1, decoder.starts)
        assertEquals(1, decoder.releases)
    }

    @Test
    fun cancellationAndDamagedFixtureBytesCannotLeaveAStartedStreamOrScoreInput() {
        val clock = Clock()
        var active = true
        val decoder = Decoder(clock).apply { onAccept = { active = false } }
        assertThrows(CancellationException::class.java) { run(decoder, clock, active = { active }) }
        assertEquals(1, decoder.starts)
        assertEquals(1, decoder.releases)
        val unused = Decoder(Clock())
        val rows = run(unused, unused.clock, fixture = { byteArrayOf(0, 1) })
        assertTrue(rows.all { it.status == VoiceBenchStatus.FAILED && it.reason == "FIXTURE_DIGEST_MISMATCH" && it.metrics.isEmpty() })
        assertEquals(0, unused.starts)
    }

    private fun run(
        decoder: Decoder,
        clock: Clock,
        active: () -> Boolean = { true },
        thermal: () -> Int? = { 0 },
        warmup: Boolean = false,
        checkpoint: (List<VoiceBenchObservation>) -> Unit = {},
        fixture: (String) -> ByteArray = { id ->
            listOf(File("src/main/assets"), File("app/src/main/assets")).map { File(it, "voice-benchmark/$id.mulaw") }.first { it.isFile }.readBytes()
        }
    ): List<VoiceBenchObservation> = voiceBenchPacedRecognitionFixtures(
        decoder,
        File("models-unused-by-scripted-decoder"),
        VoiceListeningStyle.NATURAL,
        active,
        checkpoint,
        thermalStatus = thermal,
        calibrationWarmup = warmup,
        readFixture = fixture,
        protocolFactory = { VoiceRecognitionBenchmarkProtocol(it, nowNanos = { clock.nanos }, pauseNanos = { delay -> clock.nanos += delay }) }
    )

    private class Clock(var nanos: Long = 0)

    private class Decoder(val clock: Clock, val warmup: Boolean = false) : LocalSpeechDecoder {
        var onStart: Decoder.() -> Unit = {}
        var onAccept: Decoder.() -> Unit = {}
        var onFinish: Decoder.() -> Unit = {}
        var starts = 0
        var releases = 0
        var closed = false
        var frames = 0L
        var language = ""
        var vocabulary = listOf("unconfigured")
        val stream get() = starts.toLong()
        val expected get() = voiceBenchRecordings.values.toList()[(starts - 1 - if (warmup) 1 else 0).coerceAtLeast(0)]
        private var events: ((LocalSpeechEvent) -> Unit)? = null

        override fun configureRecognition(languageTag: String, vocabulary: List<String>) {
            language = languageTag
            this.vocabulary = vocabulary
        }
        override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) {
            this.events = events
            starts++
            frames = 0
            clock.nanos += 5_000_000
            onStart()
        }
        override fun accept(samples: FloatArray) {
            frames += samples.size
            clock.nanos += 1_000_000
            onAccept()
        }
        override fun finish() {
            clock.nanos += 2_000_000
            onFinish()
        }
        override fun releaseStream() {
            releases++
            events = null
        }
        override fun close() {
            closed = true
        }
        fun emit(event: LocalSpeechEvent) {
            events?.invoke(event)
        }
        fun transcript(text: String, revision: Long, final: Boolean = false) = VoiceRecognitionTranscript(text, stream, revision, final, 16_000, frames)
    }
}
