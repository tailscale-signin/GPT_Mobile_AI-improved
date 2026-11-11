package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredKokoroOutputTest {
    @Test fun configurationStopAndCloseDoNotLoadNativePlayback() {
        var constructions = 0
        val output = DeferredKokoroOutput {
            constructions++
            FakeKokoro()
        }

        output.configureThreads(4)
        output.stop()
        output.releaseWarmModel()
        output.close()

        assertEquals(0, constructions)
    }

    @Test fun firstPlaybackLoadsOnceAndAppliesThePendingThreadCount() {
        var constructions = 0
        val delegate = FakeKokoro()
        val output = DeferredKokoroOutput {
            constructions++
            delegate
        }
        output.configureThreads(4)

        output.prewarm(KokoroVoice.HEART)
        output.prewarm(KokoroVoice.ADAM)

        assertEquals(1, constructions)
        assertEquals(4, delegate.threads)
        assertEquals(listOf(KokoroVoice.HEART, KokoroVoice.ADAM), delegate.prewarmed)
        output.close()
        assertTrue(delegate.closed)
    }

    @Test fun unavailableNativePlaybackRequestsTheExistingOfflineFallback() {
        val output = DeferredKokoroOutput { null }
        var fallback = false
        output.speak("hello", KokoroVoice.EMMA, {}, { _, canFallback -> fallback = canFallback })

        assertTrue(fallback)
        assertNull(output.openReply(KokoroVoice.EMMA, {}, { _, _ -> }))
    }

    private class FakeKokoro : KokoroOutput {
        var threads = 2
        var closed = false
        val prewarmed = mutableListOf<KokoroVoice>()

        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) = complete()
        override fun configureThreads(value: Int) {
            threads = value
        }
        override fun prewarm(voice: KokoroVoice) {
            prewarmed += voice
        }
        override fun stop() = Unit
        override fun close() {
            closed = true
        }
    }
}
