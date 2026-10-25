package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkDiagnosticScenarioSummary
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkFailure
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkIntelligenceSummary
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMetricResult
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class BenchmarkResultCardTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val scrollState = ScrollState(0)
    private val profile = PlatformV2(uid = "p", name = "Requested GPU profile", model = "model", compatibleType = ClientType.LITERT_LM)
    private fun run(id: String, at: Long) = BenchmarkRun(id, "p", profile.name, "LITERT_LM", "model", "revision", true, BenchmarkMode.QUICK, at, plannedTrials = 5, requestedAccelerator = "GPU", state = "COMPLETED")
    private fun presentation(runs: List<BenchmarkRun>, rows: List<BenchmarkScoreRow> = emptyList()) = benchmarkResultPresentations(listOf(profile), runs, emptyList(), rows).single { it.mode == BenchmarkMode.QUICK }
    private fun show(result: BenchmarkResultPresentation) {
        compose.setContent { MaterialTheme { Column(Modifier.height(400.dp).testTag("benchmark_result_scroll").verticalScroll(scrollState)) { BenchmarkResultCard(result) } } }
    }

    private fun scrollToDetails() {
        val viewport = compose.onNodeWithTag("benchmark_result_scroll").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText("Advanced evidence").fetchSemanticsNode()
        val delta = action.positionInRoot.y + action.size.height / 2f - viewport.center.y
        compose.runOnIdle { scrollState.dispatchRawDelta(delta) }
        compose.waitForIdle()
    }

    @Test fun collapsedAndExpandedPercentageMetricRenderWithoutInterpretingTheUnit() {
        val result = presentation(listOf(run("current", 2000))).copy(attemptMetrics = listOf(BenchmarkMetricResult("answer_correctness", 100.0 * 18 / 21, "%", 18, 21, 47, listOf("current"), listOf("attempt-1"))))
        show(result)
        scrollToDetails()
        compose.onNodeWithText("Advanced evidence").assertIsDisplayed().performClick()
        compose.onNodeWithText("Answer accuracy 85.7% (18/21)", substring = true).assertExists()
        compose.onNodeWithText("answer correctness: 85.71%").assertExists()
        compose.onNodeWithText("18/21 · planned 47", substring = true).assertExists()
        compose.onNodeWithText("Hide details").assertIsDisplayed().performClick()
    }

    @Test fun latestFailureOnlyAttemptRemainsVisibleWithoutReplacingItWithAnOlderVerifiedResult() {
        val older = run("old-success", 1000)
        val current = run("failed-now", 2000).copy(state = "FAILED", samples = listOf(BenchmarkSample("load", "Load", "warmup", BenchmarkOutcome.ERROR, warmup = true, requestDispatched = false, failure = BenchmarkFailure("LOAD_FAILED", "load", "Backend could not load."))))
        val retained = BenchmarkScoreRow("p", "revision", "QUICK|test", listOf(older.id), 12.0, 100.0, null, null, 100.0, 100.0, null, null, true, 1, "Retained complete result", resultId = "verified-old", latestAttemptRunId = current.id)
        val result = presentation(listOf(older, current), listOf(retained))
        assertEquals(current.id, result.latestRun?.id)
        assertNull(result.attemptScore)
        assertEquals("verified-old", result.lastVerified?.resultId)
        show(result)
        compose.onNodeWithText("Latest attempt: failed").assertIsDisplayed()
        compose.onNodeWithText("Actual executed backend: Not confirmed by a native receipt").assertExists()
        compose.onNodeWithText("Current failure stage: load · LOAD_FAILED").assertExists()
        compose.onNodeWithText("Last verified result:", substring = true).assertExists()
        scrollToDetails()
        compose.onNodeWithText("Advanced evidence").assertIsDisplayed().performClick()
        compose.onNodeWithText("answer correctness: Not measured").assertExists()
    }

    @Test fun requestedGpuNameDoesNotOverrideAnActualCpuExecutionReceipt() {
        val receipt = RuntimeExecutionReceipt(nativeEntered = true, nativeReturned = true, terminalKind = "eos", engineLoad = EngineLoadReceipt("load-1", "digest", "llama-test", "GPU", "CPU", "engine-1", startedAtElapsedMs = 1, completedAtElapsedMs = 2, outcome = "loaded"))
        val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.PASSED, outputCharacters = 1, preview = "1", runtimeExecutions = listOf(receipt), requestDispatched = true, terminalReceived = true)
        show(presentation(listOf(run("current", 2000).copy(samples = listOf(sample)))))
        compose.onNodeWithText("Actual executed backend: CPU").assertExists()
        compose.onNodeWithText("1/5 planned cases observed executing · 1 cases accounted for").assertExists()
        scrollToDetails()
        compose.onNodeWithText("Advanced evidence").assertIsDisplayed().performClick()
        compose.onNodeWithText("Requested engine/backend: GPU · profile accelerator GPU").assertExists()
    }

    @Test fun unparseableIntelligenceStaysUnknownWhileScenarioRateHasItsOwnPopulation() {
        val intelligence = BenchmarkIntelligenceSummary(
            plannedQuestions = 6, gradedQuestions = 0, correctQuestions = null, unknownQuestions = 6, accuracyPercent = null,
            domains = emptyMap(), domainGradedQuestions = mapOf("math" to 0), formatPassed = 0, formatObserved = 1,
            parseableCases = 0, parseabilityObserved = 1, uniqueQuestions = 6, uniqueQuestionFamilies = 6, complete = false, fixtureVersions = setOf(4)
        )
        show(presentation(listOf(run("malformed", 2000))).copy(intelligence = intelligence, scenarios = BenchmarkDiagnosticScenarioSummary(3, 10, 10, 30.0)))
        compose.onNodeWithText("Intelligence: Unknown (ungraded) · 6 questions ungraded").assertExists()
        compose.onNodeWithText("Intelligence accuracy: 0", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Diagnostic scenario pass rate: 30.0% (3/10 graded scenarios; 10 planned)").assertExists()
        compose.onNodeWithText("Provisional · Unranked").assertExists()
        compose.onNodeWithText("Overall: Not available").assertExists()
        scrollToDetails()
        compose.onNodeWithText("Advanced evidence").assertIsDisplayed().performClick()
        compose.onNodeWithText("Intelligence format compliance: 0/1 observed cases · parseable: 0/1 observed cases").assertExists()
    }

    @Test fun anUnverifiedStoredNumberIsNeverPresentedAsAnOverallScore() {
        val score = BenchmarkScoreRow("p", "revision", "QUICK|test", listOf("current"), null, null, null, null, 100.0, 100.0, null, 99.0, false, 1, "Incomplete observations")
        show(presentation(listOf(run("current", 2000))).copy(attemptScore = score))
        compose.onNodeWithText("Provisional · Unranked").assertExists()
        compose.onNodeWithText("Overall: Not available").assertExists()
        compose.onNodeWithText("Overall: 99.0 / 100").assertDoesNotExist()
    }
}
