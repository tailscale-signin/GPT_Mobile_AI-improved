package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportArtifactStore
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BenchmarkExportContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceDiagnosticExportViewModelTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedPickerAndProviderCloseRetainCompleteVoiceExportAcrossProcessRecreation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owners = ViewModelStore()
        try {
            for (kind in listOf(BenchmarkExportContent.VOICE_PERFORMANCE, BenchmarkExportContent.VOICE_SPEECH)) {
                val folder = File(temporary.root, kind.name)
                fun artifacts() = BenchmarkExportArtifactStore({ folder })
                val saved = SavedStateHandle()
                val first = VoiceDiagnosticExportViewModel(kind, artifacts(), saved) { error("The picker never returned a URI") }
                owners.put("first", first)
                first.state.first { it.loaded }
                val body = "{\"records\":\"${"Ω文".repeat(300_000)}\",\"final\":7654321}".toByteArray()
                first.request { it.write(body) }
                val prepared = first.state.first { !it.busy && it.retained != null }
                val receipt = checkNotNull(prepared.retained)
                assertEquals(setOf(VoiceDiagnosticExportViewModel.RECEIPT_ID), saved.keys())
                assertEquals(receipt.id, saved.get<String>(VoiceDiagnosticExportViewModel.RECEIPT_ID))
                assertTrue(receipt.id.length < 64)
                assertNotNull(first.takeDestinationRequest(receipt.id))
                first.destinationFailed()
                assertFalse(first.state.value.choosingDestination)
                assertEquals(receipt, artifacts().latest())
                owners.clear()

                // A fresh owner with no saved Bundle still recovers the small on-disk receipt.
                val retryState = SavedStateHandle()
                val destination = ByteArrayOutputStream()
                var failClose = true
                val restarted = VoiceDiagnosticExportViewModel(kind, artifacts(), retryState) {
                    object : OutputStream() {
                        override fun write(value: Int) = destination.write(value)
                        override fun write(buffer: ByteArray, offset: Int, length: Int) = destination.write(buffer, offset, length)
                        override fun close() {
                            if (failClose) throw IOException("provider close failed")
                        }
                    }
                }
                owners.put("restart", restarted)
                assertEquals(receipt, restarted.state.first { it.loaded }.retained)
                restarted.request { error("Retained bytes must not be re-encoded") }
                assertNotNull(restarted.takeDestinationRequest(receipt.id))
                restarted.destinationSelected("content://test/voice-export")
                assertNotNull(restarted.state.first { !it.busy && it.notice != null }.retained)
                assertEquals(receipt, artifacts().latest())
                failClose = false
                destination.reset()
                restarted.request { error("Retained bytes must not be re-encoded") }
                restarted.takeDestinationRequest(receipt.id)
                restarted.destinationSelected("content://test/voice-export")
                assertNull(restarted.state.first { !it.busy && it.notice != null }.retained)
                assertTrue(body.contentEquals(destination.toByteArray()))
                assertNull(artifacts().latest())
                assertNull(retryState.get<String>(VoiceDiagnosticExportViewModel.RECEIPT_ID))
                owners.clear()
            }
        } finally {
            owners.clear()
            Dispatchers.resetMain()
        }
    }
}
