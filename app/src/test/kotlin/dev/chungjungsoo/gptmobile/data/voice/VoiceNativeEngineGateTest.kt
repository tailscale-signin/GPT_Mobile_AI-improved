package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNativeEngineGateTest {
    @Test fun warmupWaitsForDecoderReleaseAndFailureReleasesTheGate() {
        val workers = Executors.newFixedThreadPool(2)
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            val decoder = workers.submit {
                VoiceNativeEngineGate.exclusive {
                    events += "decoder-start"
                    decoding.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    events += "decoder-released"
                }
            }
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            val warmup = workers.submit {
                attempted.countDown()
                runCatching {
                    VoiceNativeEngineGate.exclusive {
                        events += "kokoro-start"
                        error("startup failed")
                    }
                }
                VoiceNativeEngineGate.exclusive { events += "next-start" }
            }
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("decoder-start"), events.toList())
            release.countDown()
            decoder.get(5, TimeUnit.SECONDS)
            warmup.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("decoder-start", "decoder-released", "kokoro-start", "next-start"), events.toList())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
