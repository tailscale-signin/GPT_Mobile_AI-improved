package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposerInputLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun removingFocusedComposerReleasesFocusWithoutDiscardingDraft() {
        var visible by mutableStateOf(true)
        var focused = false
        val input = TextFieldState("Retained draft")
        compose.setContent {
            MaterialTheme {
                Column {
                    Text("Persistent chat screen")
                    if (visible) ChatInputBox(inputState = input, attachmentsEnabled = false, onComposerFocusChanged = { focused = it })
                }
            }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.runOnIdle {
            assertTrue(focused)
            visible = false
        }
        compose.runOnIdle {
            assertFalse(focused)
            assertEquals("Retained draft", input.text.toString())
            visible = true
        }
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.runOnIdle { assertEquals("Retained draft", input.text.toString()) }
    }
}
