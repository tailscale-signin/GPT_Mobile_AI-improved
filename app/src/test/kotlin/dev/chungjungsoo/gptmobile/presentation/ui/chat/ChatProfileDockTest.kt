package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatProfileDockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dockIsBelowComposerAndSelectsResponseWithoutChangingRecipients() {
        var selected by mutableStateOf<String?>(null)
        val recipients = listOf("a", "b")
        compose.setContent {
            MaterialTheme {
                Column {
                    OutlinedTextField("Shared prompt", {}, Modifier.testTag("composer"))
                    ChatProfileDock(recipients, mapOf("a" to "Alpha", "b" to "Beta"), selected, emptySet(), emptySet(), true, false, { selected = it }, {})
                }
            }
        }
        val composer = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("chat_profile_dock").fetchSemanticsNode().boundsInRoot
        assertTrue(dock.top >= composer.bottom)
        compose.onNodeWithTag("dock_profile_a").performClick().assertIsSelected()
        assertTrue(recipients == listOf("a", "b"))
        compose.onNodeWithTag("dock_profile_combined").performClick().assertIsSelected()
    }
}
