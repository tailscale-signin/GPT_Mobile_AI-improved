package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropHost
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdropSource
import dev.chungjungsoo.gptmobile.presentation.ui.setting.decodeMediaPreview
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposerControlsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewedImageReturnsToItsOriginalComposer() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val media = File(context.cacheDir, "mcp-media").apply { mkdirs() }
        val original = File(media, "${UUID.randomUUID()}.png")
        val bitmap = android.graphics.Bitmap.createBitmap(4096, 32, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            original.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val attached = AtomicReference<File?>()
        val room = MutableStateFlow(ChatRoomV2(id = 7, title = "Image conversation"))
        val uri = android.net.Uri.parse("gptmobile://media/${original.name}")
        try {
            val previewBitmap = requireNotNull(decodeMediaPreview(original))
            try {
                assertEquals(2048, previewBitmap.width)
                assertEquals(16, previewBitmap.height)
            } finally {
                previewBitmap.recycle()
            }
            composeRule.setContent {
                val preview = rememberChatMediaPreview(room, { attached.set(File(it)) }, { error("Could not attach the preview") })
                Button(onClick = { preview(uri) }) { Text("Open test image") }
            }
            composeRule.onNodeWithText("Open test image").performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Attach to reply").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("Attach to reply").performClick()
            composeRule.waitUntil(10_000) { attached.get() != null }
            val draft = requireNotNull(attached.get())
            assertEquals(File(context.filesDir, "attachments").canonicalFile, draft.parentFile?.canonicalFile)
            assertTrue(original.readBytes().contentEquals(draft.readBytes()))
            assertTrue(draft.delete())
            attached.set(null)
            composeRule.onNodeWithText("Open test image").performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Attach to reply").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("Attach to reply").assertIsDisplayed()
            room.value = ChatRoomV2(id = 8, title = "Another conversation")
            composeRule.onNodeWithText("Attach to reply").performClick()
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Attach to reply").fetchSemanticsNodes().isEmpty() }
            composeRule.onNodeWithText("Open test image").assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(null, attached.get()) }
            assertTrue(original.exists())
        } finally {
            attached.get()?.delete()
            original.delete()
        }
    }

    @Test
    fun glassComposerKeepsSendStopAndLatestActionsIndependent() {
        var stopped = 0
        var sent = 0
        var latest = 0
        composeRule.setContent {
            MaterialTheme {
                GlassBackdropHost {
                    Box(Modifier.fillMaxSize()) {
                        // Like ChatScreen, keep both consumers outside their blur source.
                        Canvas(Modifier.fillMaxSize().glassBackdropSource()) {
                            for (x in 0..size.width.toInt() step 8) {
                                drawRect(if ((x / 8) % 2 == 0) Color.Red else Color.Blue, Offset(x.toFloat(), 0f), Size(8f, size.height))
                            }
                        }
                        Column {
                            ChatInputBox(
                                inputState = rememberTextFieldState("Follow up"),
                                isRunning = true,
                                onCancelButtonClick = { stopped++ },
                                onSendButtonClick = { sent++ }
                            )
                            ScrollToBottomButton(isGenerating = true) { latest++ }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("chat_input_glass").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("chat_send").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("chat_stop").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(1, sent)
            assertEquals(1, latest)
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.filesDir, "chat-glass-controls.png").outputStream().use {
                check(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            pixels.recycle()
        }
    }

    @Test
    fun backgroundRunStartingDoesNotTakeFocusFromADraft() {
        val running = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ChatInputBox(inputState = rememberTextFieldState("Draft"), isRunning = running.value)
            }
        }
        val editor = composeRule.onNodeWithContentDescription("Message input")
        editor.performClick().assertIsFocused()
        composeRule.runOnIdle { running.value = true }
        editor.assertIsFocused()
    }

    @Test
    fun typingFollowUpKeepsStopAndSendIndependent() {
        var stopped = 0
        var sent = 0
        val diagnosticEvents = mutableListOf<String>()
        val diagnostics = ComposerInputDiagnostics(
            generation = 1,
            originIsCurrent = { true },
            publish = { fields, _ -> diagnosticEvents += (1..8).mapNotNull { fields["composer_event_$it"] as? String } },
            elapsedMillis = android.os.SystemClock::elapsedRealtime
        )
        composeRule.setContent {
            MaterialTheme {
                ChatInputBox(
                    inputState = rememberTextFieldState("Follow up"),
                    isRunning = true,
                    diagnostics = diagnostics,
                    onCancelButtonClick = { stopped++ },
                    onSendButtonClick = {
                        assertTrue(diagnosticEvents.contains("RELEASE_SEND"))
                        sent++
                    }
                )
            }
        }
        composeRule.onNodeWithContentDescription("Message input").performClick().assertIsFocused()
        composeRule.onNodeWithTag("chat_stop").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("chat_send").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Message input").assertIsNotFocused()
        composeRule.runOnIdle {
            assertTrue(diagnosticEvents.contains("EDITOR_FOCUSED"))
            assertEquals(0, stopped)
            assertEquals(1, sent)
        }
        composeRule.onNodeWithTag("chat_stop").performClick()
        composeRule.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(1, sent)
        }
    }
}
