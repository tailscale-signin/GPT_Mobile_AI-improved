package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredKokoroOutputTest {
    @Test fun aPendingReplyIdentityAndCancellationOriginReachTheLazyBackend() {
        val delegate = FakeKokoro()
        var constructions = 0
        val output = DeferredKokoroOutput {
            constructions++
            delegate
        }
        output.bindReply("reply-one")
        output.stop("session_paused")
        assertEquals(0, constructions)
        output.prewarm(KokoroVoice.HEART)
        assertEquals(listOf("reply-one"), delegate.replies)
        output.bindReply("reply-two")
        output.stop("user_barge_in")
        assertEquals(listOf("reply-one", "reply-two"), delegate.replies)
        assertEquals(listOf("user_barge_in"), delegate.stops)
        output.close()
        output.bindReply("after-close")
        assertEquals(listOf("reply-one", "reply-two"), delegate.replies)
    }

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
        val replies = mutableListOf<String>()
        val stops = mutableListOf<String>()

        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) = complete()
        override fun configureThreads(value: Int) {
            threads = value
        }
        override fun prewarm(voice: KokoroVoice) {
            prewarmed += voice
        }
        override fun stop() = Unit
        override fun stop(reason: String) {
            stops += reason
        }
        override fun bindReply(id: String) {
            replies += id
        }
        override fun close() {
            closed = true
        }
    }
}
