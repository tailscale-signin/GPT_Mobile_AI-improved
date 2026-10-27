package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatInputBox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GitHubProgrammerHomeTest {
    @get:Rule val compose = createComposeRule()
    private val context = GitHubConversationContext("github", "owner", "repo", "feature/programmer", "a".repeat(40), "local")
    private val workspace = GitHubWorkspaceState(selection = GitHubRepositoryContext("owner", "repo", "feature/programmer"), headSha = context.commitSha)

    @Test fun commandShortcutsUseTheConversationComposerAndProfileChoice() {
        var profile by mutableStateOf("local")
        var sent = ""
        compose.setContent {
            MaterialTheme {
                val input = rememberTextFieldState()
                Column(Modifier.width(360.dp)) {
                    Column(Modifier.height(520.dp).verticalScroll(rememberScrollState())) {
                        GitHubProgrammerHome(workspace, GitHubProgrammerState(profiles = listOf(PlatformV2(uid = "local", name = "Local coder"), PlatformV2(uid = "reviewer", name = "Cloud reviewer")), profileUid = profile, context = context), { profile = it }, { text -> input.edit { append(text) } }, {}, {}, {}, {}, {}, {}, {})
                    }
                    ChatInputBox(inputState = input, placeholder = "Ask your AI programmer…", attachmentsEnabled = false, onSendButtonClick = { sent = input.text.toString() })
                }
            }
        }
        compose.onNodeWithText("Fix CI").performScrollTo().performClick()
        compose.onNodeWithTag("chat_send").performClick()
        assertTrue(sent.contains("failing GitHub checks"))
        compose.onNodeWithTag("github_main_profile").performScrollTo().performClick()
        compose.onNodeWithText("Cloud reviewer").performClick()
        compose.runOnIdle { assertEquals("reviewer", profile) }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(System.getProperty("java.io.tmpdir"), "github-programmer-home.png")
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun compactLargeFontKeepsProfileAndIndexControlsInsideTheWorkspace() {
        var indexing by mutableStateOf(false)
        var preparations = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).height(700.dp).verticalScroll(rememberScrollState())) {
                        GitHubProgrammerHome(workspace, GitHubProgrammerState(profiles = listOf(PlatformV2(uid = "local", name = "A very long programming profile name ".repeat(20))), profileUid = "local", context = context, prepareIndex = indexing), {}, {}, {}, {}, {}, {}, { indexing = it }, { preparations++ }, {})
                    }
                }
            }
        }
        val home = compose.onNodeWithTag("github_programmer_home").fetchSemanticsNode().boundsInRoot
        val profile = compose.onNodeWithTag("github_main_profile").fetchSemanticsNode().boundsInRoot
        assertTrue(profile.left >= home.left && profile.right <= home.right)
        compose.onNodeWithTag("github_index_option").performScrollTo().performClick()
        compose.onNodeWithText("Prepare next batch").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(indexing)
            assertEquals(1, preparations)
        }
    }

    @Test fun waitingAndFailedRunsNeverClaimACompletedOutcome() {
        fun run(status: String) = AgentRun("run", 1, 2, 3, "local", "CUSTOM", "model", status)
        assertEquals("Waiting", GitHubProgrammerState(runs = listOf(run("WAITING_PROVIDER_LIMIT"))).activityLabel())
        assertEquals("Needs attention", GitHubProgrammerState(runs = listOf(run("FAILED"))).activityLabel())
        assertEquals("Answer ready", GitHubProgrammerState(runs = listOf(run("COMPLETED"))).activityLabel())
    }
}
