package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkFailure
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite
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
class BenchmarkRetainedResultsTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    private fun run() = BenchmarkRun(
        "retained", "deleted-profile", "Retained local profile", "LITERT_LM", "model", "key", true, BenchmarkMode.QUICK, 1,
        state = "FAILED", plannedTrials = 5,
        samples = listOf(BenchmarkSample("load", "Load", "warmup", BenchmarkOutcome.ERROR, failure = BenchmarkFailure("LOAD_FAILED", "load", "Original failure retained.")))
    )

    @Test fun resultsAndLocalModeSelectionRemainAvailableWithoutAnyCurrentProfiles() {
        val quick = run()
        val performance = quick.copy(id = "retained-performance", profileName = "Retained local performance", mode = BenchmarkMode.PERFORMANCE)
        val results = benchmarkResultPresentations(emptyList(), listOf(quick, performance), emptyList(), emptyList())
        val scroll = LazyListState()
        compose.setContent {
            var mode by remember { mutableStateOf(BenchmarkMode.QUICK) }
            MaterialTheme {
                LazyColumn(Modifier.height(600.dp).testTag("retained_results"), state = scroll) {
                    benchmarkResultsContent(mode, true, emptyList(), results, emptyList(), emptyList(), emptyList(), { mode = it }, {})
                }
            }
        }
        compose.onNodeWithText("Retained local profile").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("retained_results").fetchSemanticsNode().boundsInRoot
        val failure = compose.onNodeWithText("Current failure stage: load · LOAD_FAILED").fetchSemanticsNode()
        compose.runOnIdle { scroll.dispatchRawDelta(failure.positionInRoot.y + failure.size.height / 2f - viewport.center.y) }
        compose.waitForIdle()
        compose.onNodeWithText("Current failure stage: load · LOAD_FAILED").assertIsDisplayed()
        compose.onNodeWithTag("retained_results").performScrollToIndex(2)
        val performanceLabel = "Local performance · ${benchmarkSuite(BenchmarkMode.PERFORMANCE).size} planned cases"
        val modeAction = compose.onNodeWithText(performanceLabel).fetchSemanticsNode()
        val modeViewport = compose.onNodeWithTag("retained_results").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { scroll.dispatchRawDelta(modeAction.positionInRoot.y + modeAction.size.height / 2f - modeViewport.center.y) }
        compose.waitForIdle()
        compose.onNodeWithText(performanceLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(performanceLabel).assertIsSelected()
        compose.onNodeWithTag("retained_results").performScrollToIndex(1)
        val performanceTitle = compose.onNodeWithText("Retained local performance").fetchSemanticsNode()
        val resultsViewport = compose.onNodeWithTag("retained_results").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { scroll.dispatchRawDelta(performanceTitle.positionInRoot.y + performanceTitle.size.height / 2f - resultsViewport.center.y) }
        compose.waitForIdle()
        compose.onNodeWithText("Retained local performance").assertIsDisplayed()
    }

    @Test fun clearingTheProfileSelectionShowsRetainedHistoryAcrossDeletedProfiles() {
        val first = run()
        val second = first.copy(id = "another-run", profileUid = "another-deleted-profile")
        val history = listOf(first, second)
        assertEquals(history, benchmarkHistoryForSelection(history, null))
        assertEquals(listOf(first), benchmarkHistoryForSelection(history, first.profileUid))
    }
}
