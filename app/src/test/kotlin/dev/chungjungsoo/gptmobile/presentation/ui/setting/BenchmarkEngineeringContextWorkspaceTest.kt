package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkContextExperiment
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringComparison
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringLane
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkEngineeringRegistry
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class BenchmarkEngineeringContextWorkspaceTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val scroll = ScrollState(0)

    @Test fun acceptedContextStartRetainsTheDefaultProfileAfterItDisappears() {
        startContextAndRemoveDefault(accepted = true)
        compose.onNodeWithText("A selected saved profile is no longer available. Select it again or explicitly choose another profile.").assertExists()
        compose.onNodeWithText("Start context experiment").assertIsNotEnabled()
    }

    @Test fun rejectedContextStartDoesNotFreezeAnUnacceptedSelection() {
        startContextAndRemoveDefault(accepted = false)
        compose.onNodeWithText("A selected saved profile is no longer available. Select it again or explicitly choose another profile.").assertDoesNotExist()
        compose.onNodeWithText("Start context experiment").assertIsEnabled()
    }

    private fun startContextAndRemoveDefault(accepted: Boolean) {
        val first = PlatformV2(uid = "a", name = "Default A", model = "model-a")
        val second = PlatformV2(uid = "b", name = "Available B", model = "model-b")
        val profiles = mutableStateOf(listOf(first, second))
        val calls = mutableListOf<List<String>>()
        val viewModel = model(accepted) { calls += it }
        show { BenchmarkEngineeringWorkspace(viewModel, profiles.value, emptyList(), busy = false, ready = true, onExport = {}) }
        clickVisible(BenchmarkEngineeringLane.EXECUTABLE_CODING.label)
        clickVisible("Start context experiment")
        assertEquals(listOf(listOf("a")), calls)
        compose.runOnIdle { profiles.value = listOf(second) }
        if (accepted) clickVisible("Overview")
        assertEquals(listOf(listOf("a")), calls)
    }

    @Test fun contextControlsDisplayAndFreezeTheSameSupplementBudget() {
        val started = mutableListOf<BenchmarkContextExperiment>()
        show { BenchmarkContextExperimentControls("Profile A", enabled = true, busy = false, taskCount = 4, repeats = 1, start = { started += it }) }
        compose.onNodeWithText("Supplement budget: 12000 bytes per task · frozen when this experiment starts").assertExists()
        clickVisible("Start context experiment")
        clickVisible("Local semantic ranking")
        clickVisible("Start context experiment")
        assertEquals(listOf("CURRENT", "TASK"), started.first().variants)
        assertEquals(listOf("CURRENT", "SEMANTIC"), started.last().variants)
        assertEquals(listOf(12_000, 12_000), started.map { it.budgetBytes })
    }

    @Test fun selectedTaskShowsTheRecordedArmFrozenBudgetAndContextReceipt() {
        val run = contextEvidenceRun()
        val profiles = listOf(PlatformV2(uid = run.profileUid, name = run.profileName, model = run.model))
        show { BenchmarkEngineeringWorkspace(model(false), profiles, listOf(run), busy = false, ready = true, onExport = {}) }
        clickVisible(BenchmarkEngineeringLane.EXECUTABLE_CODING.label)
        clickVisible("Tasks")
        compose.onNodeWithText("Retained profile · TASK · ${benchmarkResultDate(run.startedAt)}").assertExists()
        clickVisible("Inspect task evidence")
        compose.onNodeWithText("Frozen context arm: TASK · supplement budget: 8192 bytes per task").assertExists()
        compose.onNodeWithText("Repository context receipt").assertExists()
        compose.onNodeWithText("Receipt arm: TASK · revision verified: Yes · dispatched: No").assertExists()
        compose.onNodeWithText("Supplement bytes: 642 · estimated tokens: 214 (UTF-8 size estimate)").assertExists()
        compose.onNodeWithText("Preparation: cold 12 ms / warm 3 ms · Local selector and source-cache probes").assertExists()
    }

    private fun model(accepted: Boolean, onStart: (List<String>) -> Unit = {}): ProfileBenchmarkViewModel = mockk<ProfileBenchmarkViewModel>().also { model ->
        every { model.engineeringComparisons } returns MutableStateFlow(emptyList<BenchmarkEngineeringComparison>())
        every { model.batches } returns MutableStateFlow(emptyList<BenchmarkBatch>())
        every { model.engineeringGitHubConnections } returns MutableStateFlow(emptyList<ToolConnection>())
        every { model.refreshEngineeringGitHubConnections() } returns Unit
        every { model.previewEngineeringBenchmark(any(), any(), any(), any(), any(), any()) } answers {
            BenchmarkEngineeringRegistry.preview(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5))
        }
        every { model.startEngineeringBenchmark(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            onStart(firstArg())
            accepted
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { Column(Modifier.height(600.dp).testTag("context_workspace_scroll").verticalScroll(scroll)) { content() } } }
    }

    private fun clickVisible(label: String) {
        val viewport = compose.onNodeWithTag("context_workspace_scroll").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithText(label).fetchSemanticsNode()
        val delta = target.positionInRoot.y + target.size.height / 2f - viewport.center.y
        compose.runOnIdle { scroll.dispatchRawDelta(delta) }
        compose.waitForIdle()
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
    }
}
