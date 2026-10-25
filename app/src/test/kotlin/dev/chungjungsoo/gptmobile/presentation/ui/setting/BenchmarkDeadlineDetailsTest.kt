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
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkDeadlineReceipt
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionPolicy
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkProfileSettings
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSettingsReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.NativeGenerationDeadlinePolicy
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeLoadDeadlinePolicy
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeProcessDeadlinePolicy
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class BenchmarkDeadlineDetailsTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val scroll = ScrollState(0)
    private fun run(sample: BenchmarkSample) = BenchmarkRun("run", "profile", "Profile", "LITERT_LM", "model", "key", true, BenchmarkMode.FULL, 1, samples = listOf(sample), suiteVersion = 10, deadlinePolicy = "benchmark-workload-deadlines-v2")

    private fun show(run: BenchmarkRun) {
        compose.setContent { MaterialTheme { Column(Modifier.height(600.dp).testTag("deadline_scroll").verticalScroll(scroll)) { BenchmarkConfigurationDetails(run) } } }
    }

    private fun openEvidence() {
        val viewport = compose.onNodeWithTag("deadline_scroll").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithText("Configuration and runtime receipts").fetchSemanticsNode()
        val delta = target.positionInRoot.y + target.size.height / 2f - viewport.center.y
        compose.runOnIdle { scroll.dispatchRawDelta(delta) }
        compose.waitForIdle()
        compose.onNodeWithText("Configuration and runtime receipts").assertIsDisplayed().performClick()
    }

    @Test fun advancedEvidenceKeepsClientLoadAndGenerationDeadlinesDistinct() {
        val load = EngineLoadReceipt(
            "load", "artifact", "runtime", "gpu", "gpu", "engine", startedAtElapsedMs = 1, completedAtElapsedMs = 4, outcome = "loaded",
            loadDeadlinePolicy = RuntimeLoadDeadlinePolicy(180000, mapOf("BACKEND_INITIALIZATION" to 20000, "ALLOCATING_CONTEXT" to 60000), supervisorAbsoluteMs = 210000)
        )
        val config = GenerationConfigReceipt("generation", 7, "engine", null, 1024, deadlinePolicy = NativeGenerationDeadlinePolicy(120000, 30000, 45000, 10.0, 10000), observedAtElapsedMs = 5)
        val supervisor = RuntimeProcessDeadlinePolicy("gpu", 600000, 300000, 45000, 5000, 120000, prefillProgressResetsIdle = false, excludeToolWaitFromIdle = true, nativeMonitorGraceMs = 0)
        val sample = BenchmarkSample("long", "Long context", "task", BenchmarkOutcome.TIMED_OUT, deadlineReceipt = BenchmarkDeadlineReceipt(360000, "benchmark-workload-deadlines-v2", 10, "long"), runtimeExecutions = listOf(RuntimeExecutionReceipt(nativeOperationId = 7, configuration = config, engineLoad = load, deadlineKind = "prefill_stall", processDeadlinePolicy = supervisor)))
        show(run(sample))
        compose.onNodeWithText("Recorded deadlines").assertDoesNotExist()
        openEvidence()
        compose.onNodeWithText("Client total request deadline: 360000 ms").assertExists()
        compose.onNodeWithText("Client preflight limit: 60000 ms · cleanup watchdog: 15000 ms").assertExists()
        compose.onNodeWithText("Admission wait: Outside the client request deadline").assertExists()
        compose.onNodeWithText("Request policy: benchmark-workload-deadlines-v2 · suite 10 · workload long").assertExists()
        compose.onNodeWithText("Native load absolute deadline: 180000 ms").assertExists()
        compose.onNodeWithText("IPC load supervisor deadline: 210000 ms").assertExists()
        compose.onNodeWithText("Load phase backend initialization: 20000 ms").assertExists()
        compose.onNodeWithText("Process supervision 1 absolute deadline: 600000 ms").assertExists()
        compose.onNodeWithText("Process prefill deadline: 300000 ms · decode idle deadline: 45000 ms").assertExists()
        compose.onNodeWithText("Terminal-delivery deadline: 5000 ms · tool-callback deadline: 120000 ms").assertExists()
        compose.onNodeWithText("Prefill progress resets the idle window: No · observed tool waits excluded from idle clocks: Yes").assertExists()
        compose.onNodeWithText("Native generation 1 absolute deadline: 120000 ms").assertExists()
        compose.onNodeWithText("Prefill stall deadline: 30000 ms · decode stall deadline: 45000 ms").assertExists()
        compose.onNodeWithText("Minimum prefill throughput: 10.00 tokens/s · throughput window: 10000 ms").assertExists()
        compose.onNodeWithText("Recorded deadline termination: prefill_stall").assertExists()
    }

    @Test fun appAppliedInstrumentationAndStructuredOverridesDoNotBecomeNativeConfirmations() {
        val profile = BenchmarkProfileSettings("model", 1f, 0.9f, 40, 2048, true, true, "system", false, "GPU", "options")
        val settings = BenchmarkSettingsReceipt(
            BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1,
            profile,
            profile.copy(reasoning = false),
            setOf("reasoning"),
            "schema",
            appAppliedSafetyOverrides = mapOf("thinking" to "false", "speculativeDecoding" to "false"),
            appAppliedInstrumentationOverrides = mapOf("nativeMetricsEnabled" to "true")
        )
        val sampler = LocalSamplerConfig(10, 0.7f, 0.0f)
        val config = GenerationConfigReceipt(
            "generation", 7, "engine", sampler, 2048,
            effectiveSampler = sampler, effectiveOutputTokens = 1024, appliedSampler = sampler, appliedOutputTokens = 1024,
            requestedThinking = true, appliedThinking = false, requestedSpeculativeDecoding = true, appliedSpeculativeDecoding = false,
            requestedResponseSchemaDigest = "schema", appliedResponseSchemaDigest = "schema", effectiveResponseSchemaDigest = "schema",
            configurationSource = "SDK_CONFIGURATION_NOT_NATIVE_ACKNOWLEDGMENT", observedAtElapsedMs = 5
        )
        val sample = BenchmarkSample("json", "Structured JSON", "json", BenchmarkOutcome.FAILED, settingsReceipt = settings, runtimeExecutions = listOf(RuntimeExecutionReceipt(configuration = config)))
        show(run(sample))
        openEvidence()
        compose.onNodeWithText("Native metrics collection enabled: true").assertExists()
        compose.onNodeWithText("Thinking enabled: false").assertExists()
        compose.onNodeWithText("Speculative decoding / MTP enabled: false").assertExists()
        compose.onNodeWithText("Thinking requested: Enabled · app-applied: Disabled").assertExists()
        compose.onNodeWithText("Speculative decoding / MTP requested: Enabled · app-applied: Disabled").assertExists()
        compose.onNodeWithText("App-applied sampler: temperature 0.0 · top-p 0.7 · top-k 10").assertExists()
        compose.onNodeWithText("Temperature Unknown · top-p Unknown · top-k Unknown").assertExists()
        compose.onNodeWithText("Native-confirmed schema digest: Unknown").assertExists()
    }

    @Test fun historicalMissingDeadlineReceiptsStayUnknownInsteadOfAdoptingCurrentDefaults() {
        show(run(BenchmarkSample("legacy", "Legacy case", "task", BenchmarkOutcome.PARTIAL)).copy(suiteVersion = 3, deadlinePolicy = null))
        openEvidence()
        compose.onNodeWithText("Run deadline policy: Not recorded").assertExists()
        compose.onNodeWithText("Client total request deadline: Not recorded").assertExists()
        compose.onNodeWithText("Native load deadline: Not recorded").assertExists()
        compose.onNodeWithText("Process supervision deadlines: Not recorded").assertExists()
        compose.onNodeWithText("Native generation deadlines: Not recorded").assertExists()
        compose.onNodeWithText("Client total request deadline: 90000 ms").assertDoesNotExist()
        compose.onNodeWithText("Native metrics collection enabled: true").assertDoesNotExist()
    }
}
