package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w600dp-h2200dp-mdpi")
class GitHubWorkspaceFilesTest {
    @get:Rule val compose = createComposeRule()

    private fun state(complete: Boolean = true) = GitHubWorkspaceState(
        selection = GitHubRepositoryContext("owner", "repo", "working"),
        defaultBranch = "main",
        headSha = "a".repeat(40),
        canWrite = true,
        directory = "src",
        file = buildJsonObject {
            put("path", "src/example.kt")
            put("content", "before")
            put("complete_file", complete)
            put("start_line", 1)
            put("end_line", 1)
            put("total_lines", if (complete) 1 else 20)
        }
    )

    @Test fun editorStillBlocksNavigationUntilItsExactChangesAreStaged() {
        val model = mockk<GitHubWorkspaceViewModel>(relaxed = true)
        every { model.stage("src/example.kt", "before", "after") } returns true
        var dirty by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GitHubWorkspaceFiles(state(), model, "", {}, dirty, { dirty = it }, {})
                }
            }
        }
        compose.onNodeWithText("Back to files").assertIsEnabled()
        compose.onNodeWithText("File content").performTextReplacement("after")
        compose.runOnIdle { assertTrue(dirty) }
        compose.onNodeWithText("Back to files").assertIsNotEnabled()
        compose.onNodeWithText("Stage").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertFalse(dirty)
            verify(exactly = 1) { model.editDraft("src/example.kt", "after", "before") }
            verify(exactly = 1) { model.stage("src/example.kt", "before", "after") }
        }
        compose.onNodeWithText("Back to files").assertIsEnabled().performClick()
        compose.runOnIdle { verify(exactly = 1) { model.browse("src") } }
    }

    @Test fun partialSourceRemainsAnExcerptAndCannotBeStagedAsAWholeFile() {
        val model = mockk<GitHubWorkspaceViewModel>(relaxed = true)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GitHubWorkspaceFiles(state(complete = false), model, "", {}, false, {}, {})
                }
            }
        }
        compose.onNodeWithText("Bounded source excerpt. Use an exact patch to change this range safely.").assertIsDisplayed()
        compose.onNodeWithText("File content").assertDoesNotExist()
        compose.onNodeWithText("Stage").assertDoesNotExist()
        compose.onNodeWithText("Prepare an exact patch").assertIsEnabled()
        compose.runOnIdle { verify(exactly = 0) { model.stage(any(), any(), any()) } }
    }
}
