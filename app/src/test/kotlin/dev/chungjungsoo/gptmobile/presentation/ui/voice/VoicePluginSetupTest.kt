package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceInstallState
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceTransferStage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoicePluginSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entrySetupCanChooseAndDownloadAStreamingPackBeforeNativeKokoroIsInstalled() {
        var selected by mutableStateOf(VoiceSpeechBackend.CPU)
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    val backend = VoiceBackendSettings(backend = selected, loaded = true)
                    VoiceEntrySpeechSettings(backend, { selected = it }, {})
                    selected.streamingEngine()?.let { engine ->
                        StreamingVoiceModelCard(
                            engine = engine,
                            state = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED),
                            voice = KokoroVoice.HEART,
                            enabled = true,
                            selectVoice = {},
                            install = { downloads++ },
                            cancel = {},
                            remove = {},
                            preview = {},
                            previewError = null,
                            setup = true
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(VoiceSpeechBackend.CPU.label).performClick()
        compose.onNodeWithText(VoiceSpeechBackend.PHONON_CPU.label).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(VoiceSpeechBackend.PHONON_CPU, selected)
            assertEquals(0, downloads)
        }
        compose.onNodeWithTag("download_streaming_voice").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test fun anInstalledNativePackCannotMasqueradeAsTheSelectedStreamingPack() {
        val installed = LocalVoiceModelState(LocalVoiceModelStatus.READY)
        val backend = VoiceBackendSettings(
            backend = VoiceSpeechBackend.POCKET_CPU,
            loaded = true,
            factory = object : VoicePhraseEngineFactory {
                override val identity = "entry-streaming-not-ready"
                override val requiresKokoroAssets = false
                override fun isReady() = false
                override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Entry must not load an engine")
            }
        )
        assertFalse(backend.speechModelReady(installed))
        assertFalse(voiceEntryModelsReady(installed, installed, backend))
        assertTrue(voiceEntryModelsReady(installed, installed, backend, spokenReplies = false))
    }

    @Test fun queuedNetworkAndLostWorkShowTheirActualStateAndRequireAnExplicitRetry() {
        var installation by mutableStateOf(VoiceInstallState("WAITING_NETWORK", pendingReason = "CONNECTIVITY"))
        val missing = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED)
        var starts = 0
        var reports = 0
        compose.setContent { MaterialTheme { Column { VoicePluginSetup(missing, missing, missing, installation, { starts++ }, {}, { reports++ }) } } }
        compose.onNodeWithText("Waiting for an internet connection…").assertIsDisplayed()
        compose.onNodeWithText("Report download problem").assertIsDisplayed().performClick()
        assertEquals(1, reports)
        assertEquals(0, starts)
        compose.runOnIdle { installation = VoiceInstallState("FAILED", "WORK_NOT_FOUND", pendingReason = "MISSING") }
        compose.onNodeWithText("Android no longer has this download. Start it again to resume verified files.").assertIsDisplayed()
        compose.onNodeWithText("Report download problem").assertIsDisplayed()
        assertEquals(0, starts)
        compose.onNodeWithText("Resume download").performClick()
        assertEquals(1, starts)
    }

    @Test fun missingStreamingPackRemainsVisibleAfterBothOriginalPacksAreReady() {
        val installed = LocalVoiceModelState(LocalVoiceModelStatus.READY, 10, 10)
        val missing = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED, 0, 100)
        var streaming by mutableStateOf(missing)
        var starts = 0
        compose.setContent {
            MaterialTheme { Column { VoicePluginSetup(installed, installed, streaming, VoiceInstallState(), { starts++ }, {}) } }
        }
        compose.onNodeWithText("Voice Chat plugin ready").assertDoesNotExist()
        compose.onNodeWithText("Download Voice Chat plugin").performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            streaming = installed
        }
        compose.onNodeWithText("Voice Chat plugin ready").assertIsDisplayed()
    }

    @Test fun connectionAndArchivePreparationAreVisibleBeforeReady() {
        var stage by mutableStateOf(VoiceTransferStage.CONNECTING)
        compose.setContent { MaterialTheme { Column { VoiceTransferProgress(stage, 0, 132_353_410) } } }
        compose.onNodeWithText("Connecting to the voice download…").assertIsDisplayed()
        compose.runOnIdle { stage = VoiceTransferStage.EXTRACTING }
        compose.onNodeWithText("Preparing the downloaded voice pack…").assertIsDisplayed()
        compose.runOnIdle { stage = VoiceTransferStage.VERIFYING }
        compose.onNodeWithText("Verifying voice files…").assertIsDisplayed()
    }
}
