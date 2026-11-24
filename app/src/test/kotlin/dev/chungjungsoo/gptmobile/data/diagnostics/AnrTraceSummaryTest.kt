package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrTraceSummaryTest {
    @Test fun processExitBindsOnlyTheSelectedProcessAndNeverExportsRawFrames() {
        val dump = """
            ----- pid 7 at 12:00 -----
            "main"
              at android.os.Looper.foreign(Looper.java:1)
            ----- end 7 -----
            ----- pid 42 at 12:01 -----
            "main"
              at android.os.Looper.loop(Looper.java:2)
              private text
            ----- end 42 -----
        """.trimIndent()
        val fields = anrExitFields(dump.byteInputStream(), 42)
        assertEquals("MAIN_THREAD_CAPTURED", fields["anr_trace_state"])
        assertEquals(1, fields["anr_frame_count"])
        assertEquals(IssueReportContext.anrFingerprint(listOf("at android.os.Looper.loop(Looper.java:2)")), fields["exception_fingerprint"])
        assertTrue("Looper" !in fields.toString())
        assertEquals("NO_MATCHING_FRAMES", anrExitFields(dump.byteInputStream(), 45)["anr_trace_state"])
        assertEquals("UNAVAILABLE", anrExitFields(null, 42)["anr_trace_state"])
    }

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
