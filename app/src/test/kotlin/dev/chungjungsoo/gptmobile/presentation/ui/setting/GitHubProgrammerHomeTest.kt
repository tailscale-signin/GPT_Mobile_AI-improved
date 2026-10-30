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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
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

    @Test fun approvalAndProviderWaitTakePriorityOverPendingOperations() {
        val event = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent("event", "run", 1, "call", "github", "GitHub", "github_write", "github_write__github_api", "{}", null, null, "PENDING")
        val run = AgentRun("run", 1, 2, 3, "local", "CUSTOM", "model", "RUNNING")
        val approval = dev.chungjungsoo.gptmobile.data.permissions.ToolApproval("approval", "run", "github", "github_write", "hash")
        assertEquals("Needs your approval", GitHubProgrammerState(runs = listOf(run), events = listOf(event), approvals = listOf(approval)).progressDescription())
        assertEquals("Waiting", GitHubProgrammerState(runs = listOf(run.copy(status = "WAITING_PROVIDER_LIMIT")), events = listOf(event)).progressDescription())
    }

    @Test fun headerUsesChatActivityResolverAndIgnoresCompletedRunTools() {
        val active = AgentRun("active", 1, 2, 3, "local", "CUSTOM", "model", "RUNNING")
        val event = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent("event", "active", 1, "call", "github", "GitHub", "github_read", "github_read__github_api", "{}", null, null, "RUNNING")
        val stale = event.copy(eventId = "old", runId = "completed", toolName = "github_write")
        val state = GitHubProgrammerState(runs = listOf(active, active.copy(runId = "completed", status = "COMPLETED")), events = listOf(event, stale))
        assertEquals(dev.chungjungsoo.gptmobile.presentation.ui.chat.generationActivityLabel(listOf(event), emptyList()), state.progressDescription())
    }

    @Test fun fixedProgressStaysVisibleWhileConversationScrolls() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(360.dp)) {
                    GitHubChatProgress(workspace, GitHubProgrammerState(runs = listOf(AgentRun("active", 1, 2, 3, "local", "CUSTOM", "model", "RUNNING"))))
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).testTag("github_test_messages")) {
                        items(100) { androidx.compose.material3.Text("Message $it") }
                    }
                }
            }
        }
        compose.onNodeWithTag("github_test_messages").performScrollToIndex(99)
        compose.onNodeWithText("Message 99").assertIsDisplayed()
        compose.onNodeWithTag("github_chat_progress").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preparing response").assertIsDisplayed()
    }

    @Test fun commandShortcutsUseTheConversationComposerAndProfileChoice() {
        var sent = ""
        compose.setContent {
            MaterialTheme {
                val input = rememberTextFieldState()
                Column(Modifier.width(360.dp).height(800.dp)) {
                    Column(Modifier.weight(1f)) {
                        GitHubProgrammerHome(workspace, GitHubProgrammerState(profiles = listOf(PlatformV2(uid = "local", name = "Local coder")), profileUid = "local", context = context), { text -> input.edit { append(text) } }, {}, {}, {}, {}, {})
                    }
                    ChatInputBox(inputState = input, placeholder = "Ask your AI programmer…", attachmentsEnabled = false, onSendButtonClick = { sent = input.text.toString() })
                }
            }
        }
        compose.onNodeWithText("Fix CI").performClick()
        compose.onNodeWithTag("chat_send").performClick()
        assertTrue(sent.contains("failing GitHub checks"))
        compose.onNodeWithText("Prepare code context").assertDoesNotExist()
        compose.onNodeWithTag("github_main_profile").assertDoesNotExist()
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
                        GitHubProgrammerSettings(workspace, GitHubProgrammerState(profiles = listOf(PlatformV2(uid = "local", name = "A very long programming profile name ".repeat(20))), profileUid = "local", profileOverrideUid = "local", context = context, prepareIndex = indexing), {}, { indexing = it }, { preparations++ })
                    }
                }
            }
        }
        val home = compose.onRoot().fetchSemanticsNode().boundsInRoot
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
