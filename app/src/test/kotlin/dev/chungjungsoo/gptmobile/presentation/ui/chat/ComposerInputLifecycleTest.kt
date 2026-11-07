package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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

    @Test fun sendingReleasesFocusBeforeDispatchAndPreservesTheExactDraft() {
        var focused = false
        var sent: String? = null
        val input = TextFieldState("A complete draft")
        compose.setContent {
            val semanticsOwner = (LocalView.current as RootForTest).semanticsOwner
            MaterialTheme {
                ChatInputBox(inputState = input, attachmentsEnabled = false, onComposerFocusChanged = { focused = it }) {
                    assertEditorReleasedFocus(semanticsOwner)
                    assertFalse(focused)
                    sent = input.text.toString()
                }
            }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.runOnIdle { assertTrue(focused) }
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.runOnIdle { assertEquals("A complete draft", sent) }
    }

    @Test fun runStartReleasesThePreviousSessionButAllowsAFreshFollowUpWhileRunning() {
        var running by mutableStateOf(false)
        val input = TextFieldState("Saved draft")
        compose.setContent {
            MaterialTheme { ChatInputBox(inputState = input, attachmentsEnabled = false, isRunning = running) }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.runOnIdle { running = true }
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.onNodeWithContentDescription("Message input").performClick().performTextInput(" after start")
        compose.onNodeWithContentDescription("Message input").assertIsFocused()
        compose.runOnIdle { assertTrue(input.text.toString().contains("after start")) }
    }

    @Test fun keyboardShortcutReleasesActualEditorFocusBeforeDispatchAndAllowsAFreshDraft() {
        lateinit var inputModeManager: InputModeManager
        var focused = false
        var sent: String? = null
        var sends = 0
        val input = TextFieldState("Exact keyboard draft")
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            val semanticsOwner = (LocalView.current as RootForTest).semanticsOwner
            MaterialTheme {
                ChatInputBox(inputState = input, attachmentsEnabled = false, onComposerFocusChanged = { focused = it }) {
                    assertEditorReleasedFocus(semanticsOwner)
                    assertFalse(focused)
                    sent = input.text.toString()
                    sends++
                }
            }
        }
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithContentDescription("Message input").performClick().assertIsFocused()
        compose.runOnIdle {
            assertEquals(InputMode.Keyboard, inputModeManager.inputMode)
            assertTrue(focused)
        }
        compose.onNodeWithContentDescription("Message input").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.Enter)
            keyUp(Key.Enter)
            keyUp(Key.CtrlLeft)
        }
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals("Exact keyboard draft", sent)
            assertEquals("Exact keyboard draft", input.text.toString())
            assertFalse(focused)
        }
        compose.onNodeWithContentDescription("Message input").performClick().performTextInput(" another draft")
        compose.onNodeWithContentDescription("Message input").assertIsFocused()
        compose.runOnIdle {
            assertTrue(focused)
            assertTrue(input.text.toString().contains("another draft"))
            assertEquals(1, sends)
        }
    }

    @Test fun runStartDoesNotStealAnotherEditorsInputSession() {
        var running by mutableStateOf(false)
        val input = TextFieldState("Retained composer draft")
        compose.setContent {
            MaterialTheme {
                Column {
                    ChatInputBox(inputState = input, attachmentsEnabled = false, isRunning = running)
                    androidx.compose.foundation.text.BasicTextField(state = rememberTextFieldState("Other draft"), modifier = Modifier.testTag("other_editor"))
                }
            }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.onNodeWithTag("other_editor").performClick().assertIsFocused()
        compose.runOnIdle { running = true }
        compose.onNodeWithTag("other_editor").assertIsFocused()
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.runOnIdle { assertEquals("Retained composer draft", input.text.toString()) }
    }

    @Test fun disposingAnUnfocusedComposerDoesNotDismissAnotherEditorsSession() {
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column {
                    if (visible) ChatInputBox(inputState = rememberTextFieldState("Retained"), attachmentsEnabled = false)
                    androidx.compose.foundation.text.BasicTextField(state = rememberTextFieldState("Other draft"), modifier = Modifier.testTag("other_editor"))
                }
            }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.onNodeWithTag("other_editor").performClick()
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("other_editor").assertIsFocused()
    }

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

    private fun assertEditorReleasedFocus(owner: SemanticsOwner) {
        // Read fresh semantics on the dispatch thread; the reporting callback alone
        // must not be able to conceal an editor that still owns the input session.
        val editor = owner.getAllSemanticsNodes(mergingEnabled = true).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Message input") == true
        }
        assertFalse("Editor still owns focus at dispatch", editor.config[SemanticsProperties.Focused])
    }
}
