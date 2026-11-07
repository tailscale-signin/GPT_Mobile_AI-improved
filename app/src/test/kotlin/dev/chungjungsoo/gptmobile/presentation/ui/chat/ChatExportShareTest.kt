package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatExportShareTest {
    @Test fun chooserRetainsTheKnownMimeWithoutConsultingAContentProvider() {
        // No provider is registered for this URI. Inferring MIME through the
        // resolver would lose the export type and can perform first-use disk I/O.
        val uri = Uri.parse("content://unregistered.test/exports/opaque-name")
        listOf("application/zip", "application/x-ndjson", "text/plain", "text/markdown").forEach { mime ->
            val chooser = chatExportChooser(uri, "opaque-name", mime)
            val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            listOf(chooser, send).forEach { intent ->
                val clip = requireNotNull(intent.clipData)
                assertEquals("opaque-name", clip.description.label)
                assertEquals(1, clip.description.mimeTypeCount)
                assertEquals(mime, clip.description.getMimeType(0))
                assertEquals(1, clip.itemCount)
                assertEquals(uri, clip.getItemAt(0).uri)
            }
            assertEquals(mime, send.type)
        }
    }

    @Test fun chooserGrantsOnlySelectedRecipientReadAccessToTheContentArchive() {
        val uri = Uri.parse("content://unregistered.test/exports/evidence.zip")
        val chooser = chatExportChooser(uri, "evidence.zip", "application/zip")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        listOf(chooser, send).forEach { intent ->
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertFalse(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        }
    }

    @Test fun chooserRejectsFileAndNetworkUris() {
        listOf("file:///private/diagnostics.zip", "https://example.test/export.zip").forEach { raw ->
            assertTrue(runCatching { chatExportChooser(Uri.parse(raw), "diagnostics.zip", "application/zip") }.isFailure)
        }
    }
}
