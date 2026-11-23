package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonArm
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonCase
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonCriterion
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLeg
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLegStatus
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonMeasurements
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonResult
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h1000dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class PrivacyComparisonPanelTest {
    @get:Rule val compose = createComposeRule()
    private val profile = PrivacyComparisonProfileChoice("synthetic-profile", "Synthetic profile", "fictional-model", ClientType.OPENAI)

    @Test fun openingAndChoosingAProfileDoNotRunUntilExplicitRunAndActiveControlsRemainCancellable() {
        var state by mutableStateOf(PrivacyComparisonUiState(profiles = listOf(profile)))
        var selections = 0
        var runs = 0
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PrivacyComparisonContent(
                        state,
                        onSelectProfile = {
                            selections++
                            state = state.copy(selectedUid = it)
                        },
                        onRun = {
                            runs++
                            state = state.copy(running = true, runProfile = profile, result = PrivacyComparisonResult("synthetic-run"))
                        },
                        onCancel = {
                            cancellations++
                            state = state.copy(running = false, result = state.result?.copy(status = PrivacyComparisonStatus.CANCELLED))
                        }
                    )
                }
            }
        }
        compose.onNodeWithTag("privacy_comparison_run").assertDoesNotExist()
        compose.onNodeWithTag("privacy_comparison_toggle").performClick()
        compose.onNodeWithTag("privacy_comparison_run").assertIsNotEnabled()
        compose.onNodeWithTag("privacy_comparison_profile").performClick()
        compose.onNodeWithTag("privacy_comparison_profile_${profile.uid}").performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(0, runs)
        }
        compose.onNodeWithTag("privacy_comparison_run").assertIsEnabled().performClick()
        compose.onNodeWithTag("privacy_comparison_run").assertIsNotEnabled()
        compose.onNodeWithTag("privacy_comparison_profile").assertIsNotEnabled()
        compose.onNodeWithTag("privacy_comparison_toggle").assertIsNotEnabled()
        compose.onNodeWithTag("privacy_comparison_cancel").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, runs)
            assertEquals(1, cancellations)
        }
        compose.onNodeWithText("Comparison cancelled; recorded responses retained").assertExists()
    }

    @Test fun everyIncompleteLegRemainsUnscoredAndUnknownTimingsAreNeverRenderedAsZero() {
        var legStatus by mutableStateOf(PrivacyComparisonLegStatus.PARTIAL)
        var routeKind by mutableStateOf<String?>(null)
        compose.setContent {
            val leg = PrivacyComparisonLeg(
                caseId = PrivacyComparisonCase.TRAVEL.id,
                arm = PrivacyComparisonArm.ON,
                status = legStatus,
                answer = "Synthetic unfinished answer",
                criteria = listOf(PrivacyComparisonCriterion("synthetic", "Synthetic criterion", true)),
                elapsedMs = 22,
                measurements = PrivacyComparisonMeasurements(routeKind = routeKind)
            )
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PrivacyComparisonContent(
                        PrivacyComparisonUiState(
                            runProfile = profile.copy(provider = ClientType.LITERT_LM),
                            result = PrivacyComparisonResult("synthetic-run", status = PrivacyComparisonStatus.INCOMPLETE, legs = listOf(leg))
                        ),
                        {},
                        {},
                        {}
                    )
                }
            }
        }
        compose.onNodeWithTag("privacy_comparison_toggle").performClick()
        PrivacyComparisonLegStatus.entries.filter { it != PrivacyComparisonLegStatus.COMPLETE }.forEach { status ->
            compose.runOnIdle { legStatus = status }
            compose.onNodeWithTag("privacy_comparison_pair_status_travel").assertTextContains("Pair incomplete.")
            compose.onNodeWithText("Fixture criteria: not scored.").assertExists()
            compose.onNodeWithText("Fixture criteria met:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("First answer text: Not observed. Completed answer: Not observed. Elapsed: 22 ms.").assertExists()
            compose.onNodeWithText("Local masking: Not observed. Local restoration: Not observed.").assertExists()
            compose.onNodeWithText("Provider-reported tokens: input unknown, output unknown.").assertExists()
            compose.onNodeWithText("Comparison finished").assertDoesNotExist()
        }
        compose.runOnIdle { routeKind = "LOCAL" }
        compose.onNodeWithText("Observed request route: On device.").assertExists()
        compose.onNodeWithText("Local masking: Not applicable (on-device route). Local restoration: Not applicable (on-device route).").assertExists()
    }

    @Test fun strictRouteDifferencesKeepSeparateRubricResultsAndMissingPairsCannotCompleteTheRun() {
        val off = PrivacyComparisonLeg(
            caseId = PrivacyComparisonCase.TRAVEL.id,
            arm = PrivacyComparisonArm.OFF,
            status = PrivacyComparisonLegStatus.COMPLETE,
            answer = "Synthetic answer",
            criteria = listOf(PrivacyComparisonCriterion("synthetic", "Synthetic criterion", true)),
            actualRuntime = "synthetic-runtime",
            effectiveOutputTokens = 512
        )
        val on = off.copy(
            arm = PrivacyComparisonArm.ON,
            measurements = PrivacyComparisonMeasurements(comparisonLimitation = "STRICT_ROUTE_SETTINGS_UNVERIFIED")
        )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PrivacyComparisonContent(
                        PrivacyComparisonUiState(
                            runProfile = profile,
                            result = PrivacyComparisonResult("synthetic-run", status = PrivacyComparisonStatus.COMPLETE, legs = listOf(off, on))
                        ),
                        {},
                        {},
                        {}
                    )
                }
            }
        }
        compose.onNodeWithTag("privacy_comparison_toggle").performClick()
        compose.onNodeWithTag("privacy_comparison_pair_status_travel").assertTextContains("different or unverified settings")
        compose.onAllNodesWithText("Fixture criteria met: 1/1.").assertCountEquals(2)
        compose.onNodeWithText("Comparison incomplete").assertExists()
        compose.onNodeWithText("Comparison finished").assertDoesNotExist()
        compose.onNodeWithText("Reported backend and output limit match.").assertDoesNotExist()
        compose.onNodeWithText("No eligible pairs for timing summaries yet.").assertExists()
    }

    @Test fun timingSummaryShowsPerArmSampleCountsPercentilesAndTheSmallSampleLimit() {
        fun leg(case: PrivacyComparisonCase, arm: PrivacyComparisonArm, first: Long, complete: Long, masking: Long? = null) = PrivacyComparisonLeg(
            caseId = case.id,
            arm = arm,
            status = PrivacyComparisonLegStatus.COMPLETE,
            answer = "Synthetic answer",
            criteria = listOf(PrivacyComparisonCriterion("synthetic", "Synthetic criterion", case != PrivacyComparisonCase.EMAIL)),
            firstTextMs = first,
            completionMs = complete,
            actualRuntime = "synthetic-runtime",
            effectiveOutputTokens = 512,
            measurements = PrivacyComparisonMeasurements(
                maskingNanos = masking,
                restorationNanos = masking?.plus(1_000),
                routeKind = if (arm == PrivacyComparisonArm.OFF) "ORDINARY_TEXT" else "MASKED_TEXT"
            )
        )
        val result = PrivacyComparisonResult(
            "synthetic-timing",
            status = PrivacyComparisonStatus.INCOMPLETE,
            warmupCompleted = 2,
            legs = listOf(
                leg(PrivacyComparisonCase.TRAVEL, PrivacyComparisonArm.OFF, 10, 40),
                leg(PrivacyComparisonCase.TRAVEL, PrivacyComparisonArm.ON, 20, 60, 2_000),
                leg(PrivacyComparisonCase.EMAIL, PrivacyComparisonArm.OFF, 30, 80),
                leg(PrivacyComparisonCase.EMAIL, PrivacyComparisonArm.ON, 40, 100, 6_000)
            )
        )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PrivacyComparisonContent(PrivacyComparisonUiState(runProfile = profile, result = result), {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("privacy_comparison_toggle").performClick()
        compose.onNodeWithText("2 scored pairs in this group.").assertExists()
        compose.onNodeWithText("First answer text: n=2; p50 10 ms; p95 30 ms.").assertExists()
        compose.onNodeWithText("First answer text: n=2; p50 20 ms; p95 40 ms.").assertExists()
        compose.onNodeWithText("Completed answer: n=2; p50 40 ms; p95 80 ms.").assertExists()
        compose.onNodeWithText("Completed answer: n=2; p50 60 ms; p95 100 ms.").assertExists()
        compose.onNodeWithText("Local masking: n=2; p50 0.002 ms; p95 0.006 ms.").assertExists()
        compose.onNodeWithText("Local restoration: n=2; p50 0.003 ms; p95 0.007 ms.").assertExists()
        compose.onNodeWithText("With at most four pairs, p95 is the largest observed value.", substring = true).assertExists()
        compose.onNodeWithText("Comparison finished").assertDoesNotExist()
    }
}
