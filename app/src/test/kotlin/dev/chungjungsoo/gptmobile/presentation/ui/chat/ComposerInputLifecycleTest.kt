package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
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
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerInputLifecycleTest {
    private val compose = createComposeRule()

    // Keep surfaces available through the inner Compose rule's Activity teardown.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        object : ExternalResource() {
            private var previousRealDrawTraversals: String? = null

            override fun before() {
                // API 34 magnifiers use BLAST surfaces. Robolectric returns null
                // for those surfaces unless native graphics and real drawing are enabled.
                previousRealDrawTraversals = System.getProperty(REAL_DRAW_TRAVERSALS)
                System.setProperty(REAL_DRAW_TRAVERSALS, "true")
            }

            override fun after() {
                val previous = previousRealDrawTraversals
                if (previous == null) System.clearProperty(REAL_DRAW_TRAVERSALS) else System.setProperty(REAL_DRAW_TRAVERSALS, previous)
            }
        }
    ).around(compose)

    @Test fun sendingReleasesFocusBeforeDispatchAndPreservesTheExactDraft() {
        var focused = false
        var sent: String? = null
        val draft = "  A complete draft\nwith Unicode \uD83D\uDCAC and trailing spaces  \n"
        val input = TextFieldState(draft)
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
        compose.runOnIdle {
            assertFalse(focused)
            assertEquals(draft, sent)
            assertEquals(draft, input.text.toString())
        }
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
        compose.runOnIdle { assertEquals("Saved draft", input.text.toString()) }
        compose.onNodeWithContentDescription("Message input").performClick().performTextInput(" after start")
        compose.onNodeWithContentDescription("Message input").assertIsFocused()
        compose.runOnIdle { assertEquals("Saved draft after start", input.text.toString()) }
    }

    @Test fun runStartAndSendDoNotDismissAnotherEditorsInputSession() {
        var running by mutableStateOf(false)
        var sent = false
        var keyboardHides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() {
                keyboardHides++
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                MaterialTheme {
                    Column {
                        ChatInputBox(inputState = rememberTextFieldState("Retained"), attachmentsEnabled = false, isRunning = running) { sent = true }
                        androidx.compose.foundation.text.BasicTextField(state = rememberTextFieldState("Other draft"), modifier = Modifier.testTag("other_editor"))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.onNodeWithTag("other_editor").performClick()
        compose.runOnIdle { running = true }
        compose.onNodeWithTag("other_editor").assertIsFocused()
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithTag("other_editor").assertIsFocused()
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.runOnIdle {
            assertTrue(sent)
            assertEquals(0, keyboardHides)
        }
    }

    @Test fun releasedComposerDoesNotAddAKeyboardTraversalStop() {
        var running by mutableStateOf(false)
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                Column {
                    androidx.compose.foundation.text.BasicTextField(state = rememberTextFieldState("Other draft"), modifier = Modifier.testTag("other_editor"))
                    ChatInputBox(inputState = rememberTextFieldState("Retained"), attachmentsEnabled = false, isRunning = running)
                }
            }
        }
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.runOnIdle { assertTrue(focusManager.moveFocus(FocusDirection.Previous)) }
        compose.onNodeWithTag("other_editor").assertIsFocused()
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.runOnIdle { running = true }
        compose.onNodeWithContentDescription("Message input").assertIsNotFocused()
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.runOnIdle { assertTrue(focusManager.moveFocus(FocusDirection.Previous)) }
        compose.onNodeWithTag("other_editor").assertIsFocused()
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

    private companion object {
        const val REAL_DRAW_TRAVERSALS = "robolectric.useRealDrawTraversals"
    }
}
