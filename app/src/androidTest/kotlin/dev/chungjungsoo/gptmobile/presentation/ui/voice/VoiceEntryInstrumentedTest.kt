package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.presentation.ui.home.NewChatButton
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceEntryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedStreamingPackCanStartVoiceChatWithoutTheNativeKokoroPack() {
        var starts = 0
        val recognition = LocalVoiceModelState(LocalVoiceModelStatus.READY)
        val nativePlayback = LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED)
        val backend = VoiceBackendSettings(
            backend = VoiceSpeechBackend.PHONON_CPU,
            loaded = true,
            factory = object : VoicePhraseEngineFactory {
                override val identity = "entry-streaming-ready"
                override val requiresKokoroAssets = false
                override fun isReady() = true
                override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Entry must not load an engine")
            }
        )
        // The saved-message controls use this same readiness decision.
        assertTrue(backend.speechModelReady(nativePlayback))
        compose.setContent {
            MaterialTheme {
                VoiceEntryScreen(
                    VoiceEntryState(loaded = true, profiles = listOf(PlatformV2(uid = "test", name = "Test AI")), selected = "test"),
                    Offset.Unspecified,
                    {},
                    { starts++ },
                    {},
                    {},
                    modelsReady = voiceEntryModelsReady(recognition, nativePlayback, backend)
                )
            }
        }
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("voice_enable_start").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun voiceButtonIsLeftOfNewChatAndReportsItsRevealOrigin() {
        var origin = Offset.Unspecified
        compose.setContent {
            MaterialTheme {
                Row {
                    VoiceChatButton { origin = it }
                    NewChatButton {}
                }
            }
        }
        val voice = compose.onNodeWithTag("home_voice_chat_icon").assertIsDisplayed()
        val newChat = compose.onNodeWithTag("home_new_chat_icon").assertIsDisplayed()
        assertTrue(voice.fetchSemanticsNode().boundsInRoot.right <= newChat.fetchSemanticsNode().boundsInRoot.left)
        voice.performClick()
        compose.runOnIdle { assertTrue(origin != Offset.Unspecified) }
    }

    @Test fun disabledPluginWaitsForExplicitOptIn() {
        var enabled = 0
        compose.setContent {
            MaterialTheme {
                VoiceEntryScreen(
                    VoiceEntryState(loaded = true, profiles = listOf(PlatformV2(uid = "test", name = "Test AI")), selected = "test"),
                    Offset.Unspecified,
                    {},
                    { enabled++ },
                    {},
                    {}
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, enabled) }
        compose.onNodeWithTag("voice_enable_start").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, enabled) }
    }
}
