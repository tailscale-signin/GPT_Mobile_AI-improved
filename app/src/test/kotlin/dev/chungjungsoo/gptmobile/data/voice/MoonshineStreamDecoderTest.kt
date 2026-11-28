package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcript
import ai.moonshine.voice.TranscriptLine
import ai.moonshine.voice.WordTiming
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineStreamDecoderTest {
    @Test fun residentModelUsesFreshStreamsAndCanFinalizeTheSameLineIdOnLaterTurns() {
        val native = FakeNative()
        val decoder = MoonshineStreamDecoder(native)
        val first = mutableListOf<LocalSpeechEvent>()
        val second = mutableListOf<LocalSpeechEvent>()
        decoder.configure(VoiceListeningStyle.QUICK)
        decoder.start(File("model"), first::add)
        decoder.accept(FloatArray(3200))
        decoder.finish()
        decoder.releaseStream()
        decoder.start(File("model"), second::add)
        decoder.accept(FloatArray(3200))
        decoder.finish()
        decoder.releaseStream()
        assertEquals(listOf("turn 1"), first.filterIsInstance<LocalSpeechEvent.Final>().filter { it.transcript?.isFinal == true }.map { it.text })
        assertEquals(listOf("turn 2"), second.filterIsInstance<LocalSpeechEvent.Final>().filter { it.transcript?.isFinal == true }.map { it.text })
        assertEquals(1, native.loads)
        assertEquals(listOf(1, 2), native.freedStreams)
        assertEquals(0, native.freedModels)
        decoder.close()
        decoder.close()
        assertEquals(1, native.freedModels)
        assertEquals(listOf(VoiceListeningStyle.QUICK), native.styles)
    }

    @Test fun finalizationFlushesShortUtterancesBeforeTheFirstScheduledPass() {
        val native = FakeNative()
        val decoder = MoonshineStreamDecoder(native)
        val events = mutableListOf<LocalSpeechEvent>()
        decoder.start(File("model"), events::add)
        decoder.accept(FloatArray(640))
        assertEquals(0, native.passes)
        decoder.finish()
        assertEquals(1, native.passes)
        assertTrue(native.forced)
        assertEquals("turn 1", events.filterIsInstance<LocalSpeechEvent.Final>().single().text)
        decoder.close()
    }

    @Test fun streamCreationFailureStillReleasesTheLoadedModel() {
        val native = FakeNative().apply { failCreate = true }
        val decoder = MoonshineStreamDecoder(native)
        assertThrows(IllegalStateException::class.java) { decoder.start(File("model")) {} }
        decoder.close()
        assertEquals(1, native.freedModels)
        assertTrue(native.freedStreams.isEmpty())
    }

    @Test fun modelFactorySelectsThePublishedNativeArchitectureAndMatchingTimingOption() {
        val choices = listOf(
            Triple(VoiceRecognitionModel.MOONSHINE_TINY, JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING, false),
            Triple(VoiceRecognitionModel.MOONSHINE_SMALL, JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING, true),
            Triple(VoiceRecognitionModel.MOONSHINE_MEDIUM, JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING, true)
        )
        for ((model, architecture, wordTimings) in choices) {
            val arguments = mutableListOf<Pair<Int, Boolean>>()
            val native = FakeNative()
            val decoder = MoonshineStreamDecoder.forModel(model) { selected, timing ->
                arguments += selected to timing
                native
            }
            decoder.start(File(model.id)) {}
            assertEquals(listOf(architecture to wordTimings), arguments)
            assertEquals(1, native.loads)
            decoder.close()
        }
        assertThrows(IllegalArgumentException::class.java) { MoonshineStreamDecoder.forModel(VoiceRecognitionModel.ZIPFORMER_EN) }
    }

    @Test fun absoluteWordTimestampsAreNotOffsetByTheLineStartAgain() {
        val native = FakeNative().apply {
            transcripts += transcript(line(1, "Hello", true, 2f, word("Hello", 2.25f, 2.75f)))
        }
        val events = mutableListOf<LocalSpeechEvent>()
        val decoder = MoonshineStreamDecoder(native)
        decoder.start(File("model"), events::add)
        decoder.accept(FloatArray(48_000))
        val observed = requireNotNull(events.filterIsInstance<LocalSpeechEvent.Final>().single().transcript)
        assertEquals(1, observed.timings.size)
        assertEquals(36_000L, observed.timings.single().startFrame)
        assertEquals(44_000L, observed.timings.single().endFrame)
        assertEquals(VoiceRecognitionTimingProvenance.WORD_ALIGNMENT, observed.timings.single().provenance)
        assertEquals(null, observed.timingIssue)
        decoder.close()
    }

    @Test fun emptyFinalLinePreservesItsWordsAndEarlierCompletedLines() {
        val native = FakeNative().apply {
            transcripts += transcript(
                line(1, "Already.", true, 0f, word("Already.", .1f, .3f)),
                line(2, "current words", false, 1f, word("current", 1.2f, 1.4f), word("words", 1.5f, 1.8f))
            )
            transcripts += transcript(line(2, "", true, 1f))
        }
        val events = mutableListOf<LocalSpeechEvent>()
        val decoder = MoonshineStreamDecoder(native)
        decoder.start(File("model"), events::add)
        decoder.accept(FloatArray(48_000))
        val partial = requireNotNull(events.filterIsInstance<LocalSpeechEvent.Partial>().single().transcript)
        decoder.finish()
        val final = events.filterIsInstance<LocalSpeechEvent.Final>().last()
        val timed = requireNotNull(final.transcript)
        assertEquals("Already. current words", final.text)
        assertEquals(partial.timings, timed.timings)
        assertTrue(timed.isFinal)
        assertTrue(timed.revision > partial.revision)
        assertEquals(partial.streamId, timed.streamId)
        decoder.close()
    }

    @Test fun aNewStreamDoesNotRetainOldWordTimingOrStreamIdentity() {
        val native = FakeNative().apply {
            transcripts += transcript(line(1, "First", true, 0f, word("First", .1f, .2f)))
            transcripts += transcript(line(1, "Second", true, 0f, word("Second", .3f, .4f)))
        }
        val events = mutableListOf<LocalSpeechEvent>()
        val decoder = MoonshineStreamDecoder(native)
        repeat(2) {
            decoder.start(File("model"), events::add)
            decoder.accept(FloatArray(16_000))
            decoder.releaseStream()
        }
        val finals = events.filterIsInstance<LocalSpeechEvent.Final>().map { requireNotNull(it.transcript) }
        assertNotEquals(finals[0].streamId, finals[1].streamId)
        assertEquals(listOf("First", "Second"), finals.map { it.text })
        assertEquals(listOf(1, 1), finals.map { it.timings.size })
        assertEquals(1, native.loads)
        decoder.close()
    }

    @Test fun keytermsUseCommaSeparatedPhrasesAndEmptySelectionClearsNativeBias() {
        val native = FakeNative()
        val decoder = MoonshineStreamDecoder(native)
        decoder.configureRecognition("en-US", listOf("OpenAI", "GPT Mobile", "bad,term", "private\nline"))
        decoder.start(File("model")) {}
        decoder.releaseStream()
        decoder.configureRecognition("en-GB", emptyList())
        decoder.start(File("model")) {}
        assertEquals(listOf("OpenAI,GPT Mobile", ""), native.keyterms)
        assertEquals(1, native.loads)
        assertThrows(IllegalArgumentException::class.java) { decoder.configureRecognition("fr-FR", listOf("bonjour")) }
        decoder.close()
    }

    @Test fun nativeKeytermFailureCannotStartAnUnconfiguredStream() {
        val native = FakeNative().apply { keytermResult = -1 }
        val decoder = MoonshineStreamDecoder(native)
        assertThrows(IllegalStateException::class.java) { decoder.start(File("model")) {} }
        assertEquals(0, native.streams)
        decoder.close()
        assertEquals(1, native.freedModels)
    }

    @Test fun invalidNativeWordBoundsKeepTextWithoutInventedTiming() {
        val native = FakeNative().apply { transcripts += transcript(line(1, "Hello", true, 0f, word("Hello", Float.NaN, .5f))) }
        val events = mutableListOf<LocalSpeechEvent>()
        val decoder = MoonshineStreamDecoder(native)
        decoder.start(File("model"), events::add)
        decoder.accept(FloatArray(16_000))
        val timed = requireNotNull(events.filterIsInstance<LocalSpeechEvent.Final>().single().transcript)
        assertEquals("Hello", timed.text)
        assertTrue(timed.timings.isEmpty())
        assertEquals(VoiceRecognitionTimingIssue.INVALID_BOUNDARY, timed.timingIssue)
        decoder.close()
    }

    @Test fun forcedFinishPublishesRefinedWordsForALineAlreadyReportedAsAnEndpoint() {
        val native = FakeNative().apply {
            transcripts += transcript(line(1, "Hello world", true, 0f))
            transcripts += transcript(line(1, "Hello world", true, 0f, word("Hello", .1f, .3f), word("world", .5f, .8f)))
        }
        val events = mutableListOf<LocalSpeechEvent>()
        val decoder = MoonshineStreamDecoder(native)
        decoder.start(File("model"), events::add)
        decoder.accept(FloatArray(16_000))
        val endpoint = requireNotNull(events.filterIsInstance<LocalSpeechEvent.Final>().single().transcript)
        assertTrue(endpoint.timings.isEmpty())
        assertEquals(false, endpoint.isFinal)

        decoder.finish()
        val terminal = events.filterIsInstance<LocalSpeechEvent.Final>().mapNotNull { it.transcript }.single { it.isFinal }
        assertEquals("Hello world", terminal.text)
        assertEquals(listOf(1_600L, 8_000L), terminal.timings.map { it.startFrame })
        assertEquals(listOf(4_800L, 12_800L), terminal.timings.map { it.endFrame })
        assertEquals(endpoint.streamId, terminal.streamId)
        assertTrue(terminal.revision > endpoint.revision)
        decoder.close()
    }

    @Test fun finalAggregateUsesTheLatestSourceWhenACompletedLineIsCorrectedDuringFlush() {
        val native = FakeNative().apply {
            transcripts += transcript(line(1, "Old word", true, 0f, word("Old", .1f, .2f), word("word", .3f, .4f)))
            transcripts += transcript(line(1, "New words", true, 0f, word("New", .1f, .25f), word("words", .3f, .6f)))
        }
        val events = mutableListOf<LocalSpeechEvent>()
        val decoder = MoonshineStreamDecoder(native)
        decoder.start(File("model"), events::add)
        decoder.accept(FloatArray(16_000))
        decoder.finish()
        val terminal = events.filterIsInstance<LocalSpeechEvent.Final>().mapNotNull { it.transcript }.single { it.isFinal }
        assertEquals("New words", terminal.text)
        assertEquals(9_600L, terminal.timings.last().endFrame)
        assertEquals("words", terminal.text.substring(terminal.timings.last().range))
        decoder.close()
    }

    private fun transcript(vararg lines: TranscriptLine) = Transcript().apply { this.lines = lines.toList() }

    private fun line(identity: Long, text: String, complete: Boolean, start: Float, vararg words: WordTiming) = TranscriptLine().apply {
        id = identity
        this.text = text
        isNew = true
        hasTextChanged = true
        isComplete = complete
        startTime = start
        duration = 1f
        this.words = words.toList()
    }

    private fun word(text: String, start: Float, end: Float) = WordTiming().apply {
        word = text
        this.start = start
        this.end = end
        confidence = 1f
    }

    private class FakeNative : MoonshineNativeApi {
        var loads = 0
        var streams = 0
        var passes = 0
        var forced = false
        var failCreate = false
        var freedModels = 0
        val styles = mutableListOf<VoiceListeningStyle>()
        val freedStreams = mutableListOf<Int>()
        val transcripts = ArrayDeque<Transcript>()
        val keyterms = mutableListOf<String>()
        var keytermResult = 0
        override fun setKeyterms(model: Int, terms: String): Int {
            keyterms += terms
            return keytermResult
        }
        override fun load(directory: File, style: VoiceListeningStyle): Int {
            loads++
            styles += style
            return 100
        }
        override fun createStream(model: Int) = if (failCreate) -1 else ++streams
        override fun start(model: Int, stream: Int) = 0
        override fun addAudio(model: Int, stream: Int, samples: FloatArray) = 0
        override fun transcribe(model: Int, stream: Int, force: Boolean): Transcript {
            passes++
            forced = force
            if (transcripts.isNotEmpty()) return transcripts.removeFirst()
            return Transcript().apply {
                lines = listOf(
                    TranscriptLine().apply {
                        id = 1
                        text = "turn $stream"
                        isNew = true
                        hasTextChanged = true
                        isComplete = true
                    }
                )
            }
        }
        override fun stop(model: Int, stream: Int) = 0
        override fun freeStream(model: Int, stream: Int): Int {
            freedStreams += stream
            return 0
        }
        override fun freeModel(model: Int) {
            freedModels++
        }
    }
}
