package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrTraceSummaryTest {
    @Test fun collectsOnlyMainThreadAllowedFrames() {
        val trace = sequenceOf(
            "\"Binder:12\" prio=5",
            "  at dev.chungjungsoo.gptmobile.Secret.doWork(Secret.kt:12)",
            "\"main\" prio=5",
            "  at dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity.onCreate(MainActivity.kt:40)",
            "  at android.os.Looper.loop(Looper.java:200)",
            "  at com.example.thirdparty.Network.post(Secret.kt:10)",
            "\"RenderThread\" prio=-4",
            "  at android.os.Looper.loop(Looper.java:999)"
        )
        val frames = mainThreadAnrFrames(trace)
        assertEquals(2, frames.size)
        assertTrue(frames.first().contains("MainActivity.onCreate"))
        assertTrue(frames.none { it.contains("Secret") || it.contains("999") })
    }

    @Test fun retainsNoRawUnknownOrPrivateValues() {
        assertTrue(mainThreadAnrFrames(sequenceOf("\"main\"", " private token=abcdef", " at com.private.UserModel.run(Secret.java:2)")).isEmpty())
    }
}
