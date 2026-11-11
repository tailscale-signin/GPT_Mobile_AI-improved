package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOptions
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPrerequisite
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import dev.chungjungsoo.gptmobile.data.audit.AppAuditSafetyClass
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class AppAuditContentTest {
    // The v1 rule's idle synchronization uses its default unconfined dispatcher.
    @get:Rule val compose = createComposeRule()
    private val scrollState = ScrollState(0)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.height(600.dp).testTag("app_audit_test_scroll").verticalScroll(scrollState)) { content() }
            }
        }
    }

    private fun SemanticsNodeInteraction.scrollIntoView(): SemanticsNodeInteraction {
        val viewport = compose.onNodeWithTag("app_audit_test_scroll").fetchSemanticsNode().boundsInRoot
        val target = fetchSemanticsNode()
        val delta = target.positionInRoot.y + target.size.height / 2f - viewport.center.y
        // Move the known test container once, without repeatedly scheduling ScrollBy actions.
        compose.runOnIdle { scrollState.dispatchRawDelta(delta) }
        compose.waitForIdle()
        return assertIsDisplayed()
    }

    private fun batch(run: AppAuditRun, state: BenchmarkBatchState = BenchmarkBatchState.COMPLETED) = BenchmarkBatch(run.id, BenchmarkMode.APP_AUDIT, run.startedAtEpochMs, emptyList(), state = state, audit = run)

    @Test fun auditNavigationIsAvailableWithoutDebugMode() {
        var selected = -1
        show { BenchmarkNavigation(0, debugMode = false) { selected = it } }
        compose.onNodeWithText("App audit").assertIsDisplayed().performClick()
        assertEquals(APP_AUDIT_TAB, selected)
        compose.onNodeWithText("Queue").performClick()
        assertEquals(0, selected)
    }

    @Test fun startWorksWithoutAnyModelAndNetworkReadsRequireAnExplicitChoice() {
        var network by mutableStateOf(false)
        var accepted: AppAuditOptions? = null
        show {
            AppAuditControls(true, false, network, { network = it }, emptyList(), emptyList(), {}, { accepted = AppAuditOptions(network) })
        }
        compose.onNodeWithText("Include configured service read checks").assertIsOff()
        compose.onNodeWithTag("app_audit_start").scrollIntoView().assertIsEnabled().performClick()
        assertEquals(AppAuditOptions(), accepted)
        compose.onNodeWithText("Include configured service read checks").scrollIntoView().performClick()
        compose.onNodeWithTag("app_audit_start").scrollIntoView().performClick()
        assertTrue(checkNotNull(accepted).allowNetworkReads)
        assertTrue(checkNotNull(accepted).selectedProfileIds.isEmpty())
    }

    @Test fun activeBenchmarkPreventsSecondStartAndChangingItsNetworkSelection() {
        var started = false
        var changed = false
        show { AppAuditControls(true, true, false, { changed = true }, emptyList(), emptyList(), {}, { started = true }) }
        compose.onNodeWithTag("app_audit_start").scrollIntoView().assertIsNotEnabled()
        compose.onNodeWithText("Include configured service read checks").scrollIntoView().assertIsNotEnabled()
        assertFalse(started)
        assertFalse(changed)
    }

    @Test fun removedModelTargetCanBeClearedWithoutReplacingItWithAnotherProfile() {
        var selected by mutableStateOf(listOf("removed-profile"))
        show {
            AppAuditControls(true, false, false, {}, emptyList(), selected, { id -> selected = selected - id }, {})
        }
        compose.onNodeWithText("Optional model targets (1 selected)").scrollIntoView().performClick()
        compose.onNodeWithText("1 selected target is no longer available", substring = true).assertExists()
        compose.onNodeWithText("Clear model targets").scrollIntoView().performClick()
        assertTrue(selected.isEmpty())
        compose.onNodeWithText("1 selected target is no longer available", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("app_audit_start").scrollIntoView().assertIsEnabled()
    }

    @Test fun optionalModelSelectionIncludesLocalAndRemoteProfilesWithoutEnablingReads() {
        val local = PlatformV2(uid = "local", name = "Local saved model", model = "local-test", compatibleType = ClientType.LITERT_LM)
        val remote = PlatformV2(uid = "remote", name = "Remote saved model", model = "remote-test")
        var selected by mutableStateOf(emptyList<String>())
        var started = false
        show {
            AppAuditControls(true, false, false, {}, listOf(local, remote), selected, { id -> selected = if (id in selected) selected - id else selected + id }, { started = true })
        }
        compose.onNodeWithText("Optional model targets (0 selected)").scrollIntoView().performClick()
        compose.onNodeWithText("Local saved model").scrollIntoView().assertIsOff().performClick()
        assertEquals(listOf("local"), selected)
        compose.onNodeWithText("Remote saved model").scrollIntoView().assertIsOff()
        compose.onNodeWithText("Include configured service read checks").assertIsOff()
        assertFalse(started)
    }

    @Test fun completedPartialAuditDisplaysCoverageBesideScoreAndExportsThatAudit() {
        val measured = auditEntry("measured")
        val other = listOf("ui", "plugin", "device").map { auditEntry(it, AppAuditEvidenceKind.EXTERNAL_SUITE).copy(prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED) }
        val run = auditRun(listOf(measured) + other, listOf(auditResult(measured)) + other.map { auditResult(it, AppAuditOutcome.UNTESTED, attempted = false) })
        var exported = false
        show { AppAuditResultCard(batch(run), active = false, onCancel = {}, onResume = {}, onExport = { exported = true }) }
        compose.onNodeWithText("Finished · recorded check outcomes").assertIsDisplayed()
        compose.onNodeWithText("Measured check pass score:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Passing weight 1 / measured weight 1").assertExists()
        compose.onNodeWithTag("app_audit_score_and_coverage")
            .assert(hasText("1 of 4 checks verified"))
            .assert(hasText("1 of 1 measured checks passed · 0 failed · 0 blocked · 0 unavailable · 3 untested"))
        compose.onNodeWithText("Partial coverage: 3", substring = true).assertExists()
        compose.onNodeWithText("Untested or pending 3", substring = true).assertExists()
        compose.onNodeWithText("Export audit evidence").scrollIntoView().performClick()
        assertTrue(exported)
    }

    @Test fun stopRequestStaysVisibleAndCannotBeSubmittedTwice() {
        val entry = auditEntry("active")
        val run = auditRun(listOf(entry)).copy(activeProbeId = entry.id)
        var stopping by mutableStateOf(false)
        var stops = 0
        show {
            AppAuditResultCard(batch(run, BenchmarkBatchState.RUNNING), active = true, cancellationRequested = stopping, onCancel = {
                stops++
                stopping = true
            }, onResume = {}, onExport = {})
        }
        compose.onNodeWithText("App audit running").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Stop app audit").scrollIntoView().performClick()
        compose.onNodeWithText("Stopping…").assertIsNotEnabled()
        compose.onNodeWithText("Stopping app audit…").assertExists()
        assertEquals(1, stops)
        compose.onNodeWithText("Resume remaining checks").assertDoesNotExist()
    }

    @Test fun stoppedAuditOffersResumeWithoutAnyProfileQueueItems() {
        val saved = batch(auditRun(listOf(auditEntry("pending"))), BenchmarkBatchState.STOPPED)
        var resumed = false
        show { AppAuditResultCard(saved, active = false, onCancel = {}, onResume = { resumed = true }, onExport = {}) }
        compose.onNodeWithText("Measured check pass score: Not measured").assertExists()
        compose.onNodeWithText("Resume remaining checks").scrollIntoView().performClick()
        assertTrue(resumed)
        compose.onNodeWithText("Skip profile").assertDoesNotExist()
        compose.onNodeWithText("Profile 1 of 0").assertDoesNotExist()
    }

    @Test fun cachedMetadataDoesNotAppearAsExecutedToolOrScoreEvidence() {
        val entry = auditEntry("catalog", AppAuditEvidenceKind.LIVE_METADATA)
        val run = auditRun(listOf(entry), listOf(auditResult(entry, source = AppAuditObservationSource.CACHE).copy(durationMs = null)))
        show { AppAuditEntryCard(appAuditPresentation(run).components.single().entries.single()) }
        compose.onNodeWithText("Observed evidence: Cached information; live behavior not verified").assertExists()
        compose.onNodeWithText("Excluded from measured score").assertExists()
        compose.onNodeWithText("Included in measured score", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Evidence details").scrollIntoView().performClick()
        compose.onNodeWithText("Duration: Not measured").assertExists()
    }

    @Test fun currentNameCanChangeOrDisappearWhileHistoricalTargetAliasStaysVisible() {
        val entry = auditEntry("catalog").copy(targetId = "connection:one")
        val run = auditRun(listOf(entry))
        var names by mutableStateOf(mapOf("connection:one" to "Original connection"))
        show { AppAuditEntryCard(appAuditPresentation(run, names).components.single().entries.single()) }
        compose.onNodeWithText("Target: target-1").assertExists()
        compose.onNodeWithText("Current saved name: Original connection").assertExists()
        compose.runOnIdle { names = mapOf("connection:one" to "Renamed connection") }
        compose.waitForIdle()
        compose.onNodeWithText("Current saved name: Renamed connection").assertExists()
        compose.onNodeWithText("Target: target-1").assertExists()
        compose.runOnIdle { names = emptyMap() }
        compose.waitForIdle()
        compose.onNodeWithText("Current saved name unavailable; the historical target alias is retained.").assertExists()
        compose.onNodeWithText("Target: target-1").assertExists()
    }

    @Test fun cachedFailureShowsRepairRouteForOnlyItsExactCurrentConnection() {
        val entry = auditEntry("catalog", AppAuditEvidenceKind.LIVE_METADATA).copy(targetId = "connection:accepted")
        val result = auditResult(entry, AppAuditOutcome.BLOCKED, AppAuditObservationSource.CACHE, attempted = false, reason = "MCP_DISCOVERY_SCHEMA_INVALID")
            .let { it.copy(observation = it.observation.copy(retryable = false)) }
        val run = auditRun(listOf(entry), listOf(result))
        var names by mutableStateOf(mapOf("connection:accepted" to "Saved connection"))
        var opened: String? = null
        show { AppAuditEntryCard(appAuditPresentation(run, names).components.single().entries.single()) { opened = it } }
        compose.onNodeWithText("This is a retained observation.", substring = true).assertExists()
        compose.onNodeWithText("Open connection settings and checks").scrollIntoView().performClick()
        assertEquals("accepted", opened)
        compose.onNodeWithText("Evidence details").scrollIntoView().performClick()
        compose.onNodeWithText("Diagnostic code: MCP_DISCOVERY_SCHEMA_INVALID").assertExists()
        compose.onNodeWithText("Retryable: no automatic retry.", substring = true).assertExists()
        compose.onNodeWithText("Original observation time: not recorded.", substring = true).assertExists()
        compose.onNodeWithText("Observed configuration revision: not recorded.", substring = true).assertExists()
        compose.runOnIdle { names = mapOf("connection:replacement" to "Saved connection") }
        compose.waitForIdle()
        compose.onNodeWithText("Open connection settings and checks").assertDoesNotExist()
    }

    @Test fun cachedFailureShowsOriginalObservationTimeAndAgeBeforeDetailsAreOpened() {
        val entry = auditEntry("catalog", AppAuditEvidenceKind.LIVE_METADATA)
        val result = auditResult(entry, AppAuditOutcome.BLOCKED, AppAuditObservationSource.CACHE, reason = "MCP_DISCOVERY_CONFIGURATION").let {
            it.copy(startedAtEpochMs = 6_400_000, observation = it.observation.copy(observedAtEpochMs = 1_060_000))
        }
        show { AppAuditEntryCard(appAuditPresentation(auditRun(listOf(entry), listOf(result))).components.single().entries.single()) }
        compose.onNodeWithText("Original observation: ${benchmarkResultDate(1_060_000)}").scrollIntoView()
        compose.onNodeWithText("Retained evidence was 89 minutes old when this audit check began.").scrollIntoView()
        compose.onNodeWithText("Hide evidence details").assertDoesNotExist()
    }

    @Test fun invalidCredentialRepairOpensItsExactSavedConnectionOnlyAfterTheAction() {
        val entry = auditEntry("credential", AppAuditEvidenceKind.LIVE_METADATA).copy(targetId = "connection:exact")
        val result = auditResult(entry, AppAuditOutcome.FAIL, AppAuditObservationSource.NONE, attempted = false, reason = "MCP_LOCAL_CREDENTIAL_INVALID")
        var opened: String? = null
        show { AppAuditEntryCard(appAuditPresentation(auditRun(listOf(entry), listOf(result)), mapOf("connection:exact" to "Saved target")).components.single().entries.single()) { opened = it } }
        assertEquals(null, opened)
        compose.onNodeWithText("This local rejection did not send an MCP request.", substring = true).assertExists()
        compose.onNodeWithText("Repair credentials and reconnect").scrollIntoView().performClick()
        assertEquals("exact", opened)
    }

    @Test fun modelGapOnlyPreparesTheExactAcceptedProfileAndDoesNotSubstituteAReplacement() {
        val entry = auditEntry("generation", AppAuditEvidenceKind.MODEL_EXECUTION).copy(
            sourceId = "selected_models",
            probeId = "model_generation",
            targetId = "model:exact",
            safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY,
            reasonCode = "MODEL_BENCHMARK_REQUIRED"
        )
        val run = auditRun(listOf(entry))
        var names by mutableStateOf(mapOf("model:exact" to "Selected profile"))
        var prepared: AppAuditPlanEntry? = null
        show { AppAuditEntryCard(appAuditPresentation(run, names).components.single().entries.single(), onPrepareModelBenchmark = { prepared = it }) }
        assertEquals(null, prepared)
        compose.onNodeWithText("Prepare model benchmark").scrollIntoView().performClick()
        assertEquals(entry, prepared)
        compose.onNodeWithText("Review the suite and press Run", substring = true).assertExists()
        compose.runOnIdle { names = mapOf("model:replacement" to "Selected profile") }
        compose.waitForIdle()
        compose.onNodeWithText("Prepare model benchmark").assertDoesNotExist()
        compose.onNodeWithText("Target: target-1").assertExists()
    }

    @Test fun openingConnectionRepairDoesNotDispatchAndOnlyTheExplicitCheckRuns() {
        val connection = ToolConnection(connectionUid = "accepted", name = "Saved MCP", alias = "saved_mcp", type = ToolConnectionType.MCP, endpointUrl = "https://fixture.invalid/mcp", authType = ToolConnectionAuthType.OAUTH, secretRef = null, oauthClientId = null)
        var checks = 0
        var signIns = 0
        var edits = 0
        var health by mutableStateOf<ToolConnectionHealth?>(null)
        show {
            AuditConnectionRepairContent(connection, health, busy = false, onEdit = { edits++ }, onCheck = {
                checks++
                health = ToolConnectionHealth(ToolConnectionHealthStatus.CHECKING, message = "Checking saved discovery")
            }, onSignIn = { signIns++ })
        }
        assertEquals(0, checks)
        assertEquals(0, signIns)
        compose.onNodeWithText("Edit saved connection").scrollIntoView().performClick()
        assertEquals(1, edits)
        compose.onNodeWithText("Reconnect and check again").scrollIntoView().performClick()
        compose.onNodeWithText("Checking connection…").assertIsNotEnabled()
        compose.onNodeWithText("Sign in again").assertIsNotEnabled()
        assertEquals(1, checks)
        assertEquals(0, signIns)
    }

    @Test fun missingSavedConnectionHasNoRepairActionsOrFallbackTarget() {
        var actions = 0
        show { AuditConnectionRepairContent(null, null, busy = false, onEdit = { actions++ }, onCheck = { actions++ }, onSignIn = { actions++ }) }
        compose.onNodeWithText("This saved connection is no longer available.", substring = true).assertExists()
        compose.onNodeWithText("Reconnect and check again").assertDoesNotExist()
        compose.onNodeWithText("Edit saved connection").assertDoesNotExist()
        assertEquals(0, actions)
    }
}
