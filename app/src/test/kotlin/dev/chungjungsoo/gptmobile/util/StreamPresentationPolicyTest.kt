package dev.chungjungsoo.gptmobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPresentationPolicyTest {
    @Test fun largeMessagesUseFewerSnapshotsWithoutChangingAnyText() {
        assertEquals(32L, adaptiveStreamIntervalMillis(32, 10))
        assertEquals(66L, adaptiveStreamIntervalMillis(32, 40_000))
        assertEquals(150L, adaptiveStreamIntervalMillis(32, 150_000))
        assertEquals(250L, adaptiveStreamIntervalMillis(250, 150_000))
    }
}
