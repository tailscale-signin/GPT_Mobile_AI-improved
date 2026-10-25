package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkDistribution
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionPolicy
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPerformanceDimension
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPerformanceMetric
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPerformanceObservation
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPerformanceSummary
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeInspectionReceipt
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
class BenchmarkEvidenceDetailsTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val scroll = ScrollState(0)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { MaterialTheme { Column(Modifier.height(600.dp).testTag("evidence_scroll").verticalScroll(scroll)) { content() } } }
    }

    private fun clickVisible(label: String) {
        val viewport = compose.onNodeWithTag("evidence_scroll").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithText(label).fetchSemanticsNode()
        val delta = target.positionInRoot.y + target.size.height / 2f - viewport.center.y
        compose.runOnIdle { scroll.dispatchRawDelta(delta) }
        compose.waitForIdle()
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
    }

    @Test fun nativeSamplerComponentsWithoutTheirOwnAcknowledgementRemainUnknown() {
        val config = GenerationConfigReceipt(
            "generation", 7, "engine", LocalSamplerConfig(20, 0.9f, 1.0f), 256,
            effectiveSampler = LocalSamplerConfig(10, 0.7f, 0.0f), effectiveOutputTokens = 128,
            nativeAcknowledgedFields = setOf("sampler.temperature"), observedAtElapsedMs = 50
        )
        val inspection = RuntimeInspectionReceipt("inspection", "runtime", "device", "firmware", "npu", "configuration", 1, 6, "success", metadataCacheDisposition = "hit", modelBytesHashed = 0, totalMs = 5)
        val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.PASSED, runtimeExecutions = listOf(RuntimeExecutionReceipt(configuration = config)), inspectionReceipt = inspection, phaseElapsedMs = mapOf("inspect" to 1000L))
        val run = BenchmarkRun("run", "p", "Profile", "LITERT_LM", "model", "key", true, BenchmarkMode.QUICK, 1, samples = listOf(sample))
        show { BenchmarkConfigurationDetails(run) }
        clickVisible("Configuration and runtime receipts")
        compose.onNodeWithText("Temperature 0.0 · top-p Unknown · top-k Unknown").assertExists()
        compose.onNodeWithText("Output budget Unknown · context Unknown · prompt Unknown · safety Unknown").assertExists()
        compose.onNodeWithText("device qualification: NOT QUALIFIED", substring = true).assertExists()
        clickVisible("Latest inspection invocation")
        compose.onNodeWithText("success · total 5 ms").assertExists()
        compose.onNodeWithText("Observed phases: inspect: 1000 ms").assertExists()
        compose.onNodeWithText("Model bytes hashed: 0", substring = true).assertExists()
        compose.onNodeWithText("Tokenizer metadata: Not measured · not separately exposed by runtime").assertExists()
    }

    @Test fun singleObservationRetainsUnknownQuantileVarianceAndComparableChange() {
        val one = BenchmarkDistribution(1, 600.0, null, 600.0, 600.0, null, null)
        val none = BenchmarkDistribution(0, null, null, null, null, null, null)
        val metric = BenchmarkPerformanceMetric(BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT, "ms", "context", one, none, 1, 1, listOf("run"), listOf("attempt"), mapOf("CLEANUP_NOT_CONFIRMED" to 1))
        val summary = BenchmarkPerformanceSummary("run", BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1, listOf(metric), emptySet(), null, null, null, null, emptySet())
        show { BenchmarkPerformanceDetails(summary) }
        clickVisible("Performance and comparable history")
        compose.onNodeWithText("Raw median 600.00 ms · p95 Not measured · count 1").assertExists()
        compose.onNodeWithText("Raw variance Not measured · standard deviation Not measured").assertExists()
        compose.onNodeWithText("Previous comparable result: Not measured").assertExists()
        compose.onNodeWithText("Measurement sources: Not recorded").assertExists()
        compose.onNodeWithText("Observation scope: Not recorded").assertExists()
        compose.onNodeWithText("Thermal states: Not measured").assertExists()
        compose.onNodeWithText("Battery: Not measured → Not measured").assertExists()
        compose.onNodeWithText("Qualification exclusions: CLEANUP_NOT_CONFIRMED: 1").assertExists()
    }

    @Test fun sdkLoadRecordNamesItsSourceWithoutClaimingNativeExecution() {
        val load = EngineLoadReceipt(
            "load", "artifact", "sdk-runtime", "GPU", "GPU", "sdk-engine",
            startedAtElapsedMs = 1, completedAtElapsedMs = 2, outcome = "loaded",
            configurationSource = "SDK_CONFIGURATION_NOT_NATIVE_ACKNOWLEDGMENT"
        )
        val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.ERROR, runtimeExecutions = listOf(RuntimeExecutionReceipt(engineLoad = load)))
        val run = BenchmarkRun("run", "deleted", "Deleted profile", "LITERT_LM", "model", "key", true, BenchmarkMode.QUICK, 1, samples = listOf(sample))
        show { BenchmarkConfigurationDetails(run) }
        clickVisible("Configuration and runtime receipts")
        compose.onNodeWithText("Recorded engine backend: GPU", substring = true).assertExists()
        compose.onNodeWithText("Load receipt source: SDK_CONFIGURATION_NOT_NATIVE_ACKNOWLEDGMENT", substring = true).assertExists()
        compose.onNodeWithText("Native entry: Unknown", substring = true).assertExists()
        compose.onNodeWithText("Actual backend:", substring = true).assertDoesNotExist()
    }

    @Test fun nativeReportedRateRetainsSourceAndSingleSegmentScopeInAdvancedEvidence() {
        val rate = BenchmarkDistribution(1, 12.5, null, 12.5, 12.5, null, null)
        val none = BenchmarkDistribution(0, null, null, null, null, null, null)
        val metric = BenchmarkPerformanceMetric(
            BenchmarkPerformanceDimension.DECODE, "tokens/s", "context", rate, none, 1, 1, listOf("run"), listOf("attempt"), emptyMap(),
            rawObservations = listOf(BenchmarkPerformanceObservation("case", "attempt", 12.5, "native_reported_rate", "single_native_segment", 1)),
            measurementSources = mapOf("native_reported_rate" to 1)
        )
        val summary = BenchmarkPerformanceSummary("run", null, listOf(metric), emptySet(), null, null, null, null, emptySet())
        show { BenchmarkPerformanceDetails(summary) }
        clickVisible("Performance and comparable history")
        compose.onNodeWithText("Raw median 12.50 tokens/s · p95 Not measured · count 1").assertExists()
        compose.onNodeWithText("Measurement sources: native_reported_rate: 1").assertExists()
        compose.onNodeWithText("Observation source: native_reported_rate · scope: single_native_segment · 1 cases · native segment counts: 1").assertExists()
    }

    @Test fun savedProfilePolicySelectionIsIndependentFromTheSelectedTestExtent() {
        var selectedPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
        var selectedMode = BenchmarkMode.QUICK
        show {
            var policy by remember { mutableStateOf(selectedPolicy) }
            var mode by remember { mutableStateOf(selectedMode) }
            BenchmarkPlanPicker(mode, includePerformance = true) {
                mode = it
                selectedMode = it
            }
            BenchmarkPolicyPicker(policy) {
                policy = it
                selectedPolicy = it
            }
        }
        clickVisible("Saved profile settings")
        assertEquals(BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, selectedPolicy)
        assertEquals(BenchmarkMode.QUICK, selectedMode)
        val label = "Local performance · ${dev.chungjungsoo.gptmobile.data.benchmark.benchmarkSuite(BenchmarkMode.PERFORMANCE).size} planned cases"
        clickVisible(label)
        assertEquals(BenchmarkMode.PERFORMANCE, selectedMode)
        assertEquals(BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1, selectedPolicy)
    }
}
