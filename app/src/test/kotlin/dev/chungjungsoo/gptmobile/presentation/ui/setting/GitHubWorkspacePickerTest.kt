package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.chungjungsoo.gptmobile.data.github.GitHubLookupRoute
import dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubChatStarters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubWorkspacePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchesAcrossLoadedPagesWithoutDroppingTheQuery() {
        var choices by mutableStateOf(listOf("main"))
        var selected = ""
        compose.setContent {
            MaterialTheme {
                GitHubWorkspacePicker("Choose a branch", "Find branch", choices, "main", false, null, choices.size == 1, { choices = choices + "release/November" }, {}, {}, { selected = it })
            }
        }
        compose.onNodeWithText("Find branch").performTextInput("nov")
        compose.onNodeWithText("No matches on loaded pages.").assertIsDisplayed()
        compose.onNodeWithText("Load more").performClick()
        compose.onNodeWithText("main").assertDoesNotExist()
        compose.onNodeWithText("release/November").performClick()
        assertEquals("release/November", selected)
    }

    @Test fun starterBecomesAnEditableDraftAndHidesWhileThereIsText() {
        val draft = TextFieldState()
        compose.setContent {
            MaterialTheme { GitHubChatStarters(draft) }
        }
        for ((label, route) in listOf("Open pull requests" to GitHubLookupRoute.PULL_REQUESTS, "Recent commits" to GitHubLookupRoute.COMMITS, "Recent CI runs" to GitHubLookupRoute.RUNS)) {
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
            compose.onNodeWithText(label).assertDoesNotExist()
            assertEquals(route, GitHubLookupRoute.from(draft.text.toString()))
            compose.runOnIdle { draft.edit { replace(0, length, "") } }
        }
    }
}
