package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.ui.home.NewChatButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceEntryInstrumentedTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithTag("voice_enable_start").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, enabled) }
    }
}
