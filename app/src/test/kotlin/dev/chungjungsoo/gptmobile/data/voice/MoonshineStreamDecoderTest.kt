package dev.chungjungsoo.gptmobile.data.voice

import ai.moonshine.voice.Transcript
import ai.moonshine.voice.TranscriptLine
import java.io.File
import org.junit.Assert.assertEquals
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
        assertEquals(listOf("turn 1"), first.filterIsInstance<LocalSpeechEvent.Final>().map { it.text })
        assertEquals(listOf("turn 2"), second.filterIsInstance<LocalSpeechEvent.Final>().map { it.text })
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

    private class FakeNative : MoonshineNativeApi {
        var loads = 0
        var streams = 0
        var passes = 0
        var forced = false
        var failCreate = false
        var freedModels = 0
        val styles = mutableListOf<VoiceListeningStyle>()
        val freedStreams = mutableListOf<Int>()
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
