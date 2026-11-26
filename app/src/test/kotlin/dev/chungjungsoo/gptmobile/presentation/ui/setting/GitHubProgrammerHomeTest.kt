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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardTarget
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayInventoryTool
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayToolInventory
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
        var state by mutableStateOf(GitHubProgrammerState(runs = listOf(AgentRun("active", 1, 2, 3, "local", "CUSTOM", "model", "RUNNING"))))
        val preparation = "Preparing response"
        val publicProgress = "The comparison is pinned; checking the changed Kotlin sources"
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).height(360.dp)) {
                    GitHubChatProgress(state)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).testTag("github_test_messages")) {
                        items(100) { androidx.compose.material3.Text("Message $it") }
                    }
                    ChatInputBox(isRunning = true, attachmentsEnabled = false)
                }
            }
        }
        val headerBounds = compose.onNodeWithTag("github_chat_progress").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals(preparation).assertIsDisplayed()
        compose.onNodeWithTag("github_test_messages").performScrollToIndex(99)
        compose.onNodeWithText("Message 99").assertIsDisplayed()
        compose.onNodeWithTag("github_chat_progress").assertIsDisplayed()
        assertEquals(headerBounds, compose.onNodeWithTag("github_chat_progress").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals(preparation).assertIsDisplayed()
        compose.onAllNodes(hasText(preparation) or hasContentDescription(preparation)).assertCountEquals(1)

        // Selected-model progress replaces preparation in the same pinned,
        // accessible header; a generic generating override must not hide it.
        compose.runOnIdle {
            state = state.copy(
                messages = listOf(
                    MessageV2(
                        id = 3,
                        chatId = 1,
                        content = "",
                        platformType = "local",
                        currentRunId = "active",
                        timeline = listOf(
                            AssistantTimelineItem(
                                type = AssistantTimelineItemType.NOTICE,
                                content = publicProgress,
                                progressCheckpoint = true,
                                modelAuthored = true,
                                progressId = "selected-model-progress"
                            )
                        )
                    )
                )
            )
        }
        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals(publicProgress).assertIsDisplayed()
        compose.onAllNodes(hasText(publicProgress) or hasContentDescription(publicProgress)).assertCountEquals(1)
        compose.onAllNodes(hasText(preparation) or hasContentDescription(preparation)).assertCountEquals(0)
        compose.onAllNodes(hasText("Response in progress") or hasContentDescription("Response in progress")).assertCountEquals(0)
        assertEquals(headerBounds, compose.onNodeWithTag("github_chat_progress").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Message 99").assertIsDisplayed()
    }

    @Test fun chatActionsOnlyOfferAvailableDestinationsAndOpenTaskDetailsInPlace() {
        var withTask by mutableStateOf(false)
        var browsed = false
        var inventory by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                if (inventory) GitHubToolInventorySheet(null, null, null, null, onDismiss = { inventory = false })
                GitHubChatActions(
                    repositoryLoaded = true,
                    mapAvailable = false,
                    hasChanges = false,
                    hasTask = withTask,
                    onBrowseFiles = { browsed = true },
                    onOpenChecks = {},
                    onOpenMap = {},
                    onReviewChanges = {},
                    onOpenInventory = { inventory = true }
                ) { dismiss ->
                    androidx.compose.material3.TextButton(onClick = dismiss) { androidx.compose.material3.Text("Close task details") }
                }
            }
        }

        compose.onNodeWithContentDescription("GitHub chat actions").performClick()
        compose.onNodeWithText("Task details").assertDoesNotExist()
        compose.onNodeWithText("Repository map").assertDoesNotExist()
        compose.onNodeWithText("Review changes").assertDoesNotExist()
        compose.onNodeWithText("Checks & builds").assertIsDisplayed()
        compose.onNodeWithText("Repository files").performClick()
        compose.runOnIdle {
            assertTrue(browsed)
            withTask = true
        }
        compose.onNodeWithContentDescription("GitHub chat actions").performClick()
        compose.onNodeWithText("Tools and features").performClick()
        compose.onNodeWithTag("github_inventory_search").performTextInput("agent_merge_status")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Pull requests")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pull requests").performClick()
        compose.onAllNodes(hasText("Actions · ", substring = true) and hasText("agent_merge_status", substring = true), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("GitHub chat actions").performClick()
        compose.onNodeWithText("Task details").performClick()
        compose.onNodeWithText("Close task details").performClick()
        compose.onNodeWithText("Close task details").assertDoesNotExist()
    }

    @Test fun gatewayAllToolsShowsTheChosenSourceWhenAliasesRepeat() {
        val target = GatewayDashboardTarget("profile", "Gateway", "selected.test", true, null)
        val first = GatewayInventoryTool("shared_read", null, listOf("path"), id = "one", sourceId = "first", authorized = true, executableNow = true)
        val second = first.copy(id = "two", sourceId = "second", authorized = false, executableNow = false, blockedReason = "Missing scope")
        val observation = GatewayToolInventory(listOf(first, second), 2, 1, scope = "gateway")
        compose.setContent {
            MaterialTheme {
                GatewayCapabilityDashboard(target, GatewayCapabilityInventory(target.identity, observation)) {}
            }
        }
        compose.onNodeWithText("All tools").performClick()
        compose.onAllNodes(hasText("shared_read")).assertCountEquals(2)
        compose.onNodeWithTag("github_inventory_search").performTextInput("second")
        compose.onNodeWithText("shared_read").performClick()
        compose.onNodeWithText("Missing scope", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("first · Reported ready at observation").assertDoesNotExist()
    }

    @Test fun approvalInTheCurrentChatTakesPriorityOverBackgroundGatewayProgress() {
        val run = AgentRun("run", 1, 2, 3, "local", "CUSTOM", "model", "RUNNING")
        val approval = dev.chungjungsoo.gptmobile.data.permissions.ToolApproval("approval", "run", "github", "github_write", "hash")
        var state by mutableStateOf(GitHubProgrammerState(chatId = 1, runs = listOf(run), approvals = listOf(approval)))
        var owner by mutableStateOf("earlier-run")
        val job = dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob("job", "owner/repo", "work", state = "RUNNING", revision = 3, createdAt = 1.0, deadline = 500.0, message = "Running background checks")
        compose.setContent { MaterialTheme { GitHubChatProgress(state, job, engineeringRunId = owner) } }

        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals("Needs your approval").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(approvals = emptyList()) }
        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals("Preparing response")
        compose.runOnIdle { owner = run.runId }
        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals(job.message)
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
        compose.onNodeWithText("Autonomous engineering").assertDoesNotExist()
        compose.onNodeWithText("Verified Engineering Evidence").assertDoesNotExist()
        compose.onNodeWithTag("github_main_profile").assertDoesNotExist()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(System.getProperty("java.io.tmpdir"), "github-programmer-home.png")
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun gatewayProgressAcceptsHoistedStateAndShowsTheFullElapsedDuration() {
        val job = dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob(
            id = "job", repository = "owner/repo", branch = "feature/programmer",
            state = "COMPLETED", revision = 4, createdAt = 0.0, deadline = 100_000.0,
            finishedAt = 90_061.0, message = "Reviewed changes and completed checks"
        )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    GitHubChatProgress(GitHubProgrammerState(), job)
                }
            }
        }
        compose.onNodeWithTag("github_loading_activity").assertContentDescriptionEquals(job.message).assertIsDisplayed()
        compose.onNodeWithText("25:01:01").assertIsDisplayed()
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
        compose.onNodeWithText("Cache next source batch").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(indexing)
            assertEquals(1, preparations)
        }
    }

    @Test fun unavailablePinnedProfileDoesNotDisplayTheChangedDefaultAsSelected() {
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp).height(700.dp).verticalScroll(rememberScrollState())) {
                    GitHubProgrammerSettings(
                        workspace,
                        GitHubProgrammerState(
                            profiles = listOf(PlatformV2(uid = "other", name = "Other coder", enabled = true)),
                            selectedProfileUid = "pinned",
                            profileOverrideUid = "other"
                        ),
                        { selected = it },
                        {},
                        {}
                    )
                }
            }
        }
        compose.onNodeWithText("Selected profile unavailable").assertIsDisplayed()
        compose.onNodeWithText("Other coder").assertDoesNotExist()
        assertEquals(null, selected)
        compose.onNodeWithTag("github_main_profile").performClick()
        compose.onNodeWithText("Other coder").performClick()
        assertEquals("other", selected)
    }

    @Test fun everydaySwitchesWorkWithoutOpeningServicesAndAdvancedBudgetRemainsSelectable() {
        var preferences by mutableStateOf(dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride.newProfile)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp).height(700.dp).verticalScroll(rememberScrollState())) {
                    GitHubProgrammerSettings(
                        workspace,
                        GitHubProgrammerState(profileUid = "local", profileSettings = preferences),
                        {},
                        {},
                        {},
                        onSettings = { preferences = it }
                    )
                }
            }
        }
        compose.onNodeWithTag("github_check_gateway").assertDoesNotExist()
        compose.onNodeWithTag("github_chat_copilot_toggle").assertDoesNotExist()
        compose.onNodeWithText("Evidence-based progress summaries").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, preferences.evidenceElaboration) }
        compose.onNodeWithText("Desktop Gateway").performScrollTo().performClick()
        compose.onNodeWithTag("github_check_gateway").assertExists()
        compose.onNodeWithText("Advanced settings").performScrollTo().performClick()
        compose.onNodeWithTag("github_engineering_budget").performScrollTo().performClick()
        compose.onNodeWithText("Fast").performClick()
        compose.runOnIdle { assertEquals(dev.chungjungsoo.gptmobile.data.context.EngineeringMode.QUICK, preferences.engineeringMode) }
    }

    @Test fun repositoryMapIsASettingsActionAndUsesTheExistingOpenCallback() {
        var opens = 0
        var available by mutableStateOf(true)
        var inventory by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                if (inventory) GitHubToolInventorySheet(null, null, "Choose a Gateway", null, onDismiss = { inventory = false })
                Column(Modifier.width(360.dp).height(700.dp).verticalScroll(rememberScrollState())) {
                    GitHubProgrammerSettings(
                        workspace,
                        GitHubProgrammerState(context = context),
                        {},
                        {},
                        {},
                        onOpenInventory = { inventory = true },
                        onOpenRepositoryMap = if (available) {
                            { opens++ }
                        } else {
                            null
                        }
                    )
                }
            }
        }
        compose.onNodeWithTag("github_tools_features").performScrollTo().performClick()
        compose.onNodeWithText("Gateway").performClick()
        compose.onNodeWithText("Choose a Gateway").assertIsDisplayed()
        compose.onNodeWithText("Refresh inventory").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("github_repository_map").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            available = false
        }
        compose.onNodeWithText("Repository map").assertDoesNotExist()
    }

    @Test fun waitingAndFailedRunsNeverClaimACompletedOutcome() {
        fun run(status: String) = AgentRun("run", 1, 2, 3, "local", "CUSTOM", "model", status)
        assertEquals("Waiting", GitHubProgrammerState(runs = listOf(run("WAITING_PROVIDER_LIMIT"))).activityLabel())
        assertEquals("Needs attention", GitHubProgrammerState(runs = listOf(run("FAILED"))).activityLabel())
        assertEquals("Answer ready", GitHubProgrammerState(runs = listOf(run("COMPLETED"))).activityLabel())
    }
}
