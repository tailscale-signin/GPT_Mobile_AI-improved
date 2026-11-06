package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiagnosticTrackingPreferenceTest {
    private fun revision(): Long = (AppLogRecorder::class.java.getDeclaredField("preferenceRevision").apply { isAccessible = true }.get(AppLogRecorder) as AtomicLong).get()

    @Test fun delayedStartupPreferenceCannotUndoEitherNewerExplicitChoiceOrItsPendingWrite() {
        try {
            for (choice in listOf(false, true)) {
                AppLogRecorder.setEnabled(!choice)
                val readStarted = revision()
                // The retained read completes after the user changes tracking.
                AppLogRecorder.setEnabled(choice)
                val newestWrite = revision()
                assertFalse(AppLogRecorder.restoreTrackingPreference(readStarted, !choice))
                assertEquals(choice, AppLogRecorder.enabled.value)
                assertEquals("A stale load must not invalidate the user's pending preference writer", newestWrite, revision())
            }
        } finally {
            AppLogRecorder.setEnabled(false)
        }
    }

    @Test fun unchangedStartupRevisionRestoresTheRetainedPreferenceExactlyOnce() {
        try {
            AppLogRecorder.setEnabled(false)
            val readStarted = revision()
            assertTrue(AppLogRecorder.restoreTrackingPreference(readStarted, true))
            assertTrue(AppLogRecorder.enabled.value)
            assertFalse(AppLogRecorder.restoreTrackingPreference(readStarted, false))
            assertTrue(AppLogRecorder.enabled.value)
        } finally {
            AppLogRecorder.setEnabled(false)
        }
    }
}
