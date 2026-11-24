package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonArm
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonCase
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonCriterion
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonFailure
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLeg
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLegStatus
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonResult
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonStatus
import dev.chungjungsoo.gptmobile.data.privacy.privacyComparisonProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyComparisonViewModelTest {
    private val remote = PlatformV2(uid = "synthetic-remote", name = "Synthetic remote", model = "fictional-model", token = "synthetic-credential", systemPrompt = "SYNTHETIC_SAVED_INSTRUCTION")
    private val local = PlatformV2(uid = "synthetic-local", name = "Synthetic local", compatibleType = ClientType.LITERT_LM, model = "fictional-local-model")
    private val models = mutableListOf<PrivacyComparisonViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun loadingAndSelectingDoNotRunAndRapidRunClicksCaptureOneUnchangedProfile() = runTest {
        val profiles = MutableStateFlow(listOf(remote, local, remote.copy(uid = "disabled", enabled = false)))
        val captured = mutableListOf<PlatformV2>()
        val model = model(profiles) { profile, _, _ ->
            captured += profile
            awaitCancellation()
        }
        runCurrent()
        assertEquals(listOf(remote.uid, local.uid), model.state.value.profiles.map { it.uid })
        assertNull(model.state.value.selectedUid)
        model.selectProfile(remote.uid)
        runCurrent()
        assertTrue(captured.isEmpty())
        assertFalse(model.state.value.toString().contains("synthetic-credential"))
        assertFalse(model.state.value.toString().contains("SYNTHETIC_SAVED_INSTRUCTION"))

        model.runComparison()
        model.runComparison()
        runCurrent()
        assertEquals(listOf(remote), captured)
        assertNotSame(remote, captured.single())
        model.selectProfile(local.uid)
        profiles.value = listOf(remote, local.copy(name = "Other profile edited"))
        runCurrent()
        assertTrue(model.state.value.running)
        assertEquals(remote.uid, model.state.value.selectedUid)
        assertEquals(remote.model, model.state.value.runProfile?.model)
        assertEquals(1, captured.size)
        model.cancel()
        runCurrent()
    }

    @Test fun cancellationRetainsTheLatestCompletedAndPartialLegsWithoutRestarting() = runTest {
        val complete = completedLeg()
        val partial = complete.copy(arm = PrivacyComparisonArm.ON, status = PrivacyComparisonLegStatus.CANCELLED, answer = "Synthetic partial answer", criteria = emptyList())
        var calls = 0
        val model = model(MutableStateFlow(listOf(remote))) { _, runId, onUpdate ->
            calls++
            val result = PrivacyComparisonResult(runId, legs = listOf(complete), warmupCompleted = 2)
            onUpdate(result)
            try {
                awaitCancellation()
            } finally {
                onUpdate(result.copy(status = PrivacyComparisonStatus.CANCELLED, legs = listOf(complete, partial)))
            }
        }
        runCurrent()
        model.selectProfile(remote.uid)
        model.runComparison()
        runCurrent()
        model.cancel()
        runCurrent()

        val cancelled = requireNotNull(model.state.value.result)
        assertEquals(PrivacyComparisonStatus.CANCELLED, cancelled.status)
        assertEquals(PrivacyComparisonFailure.CANCELLED, cancelled.failure)
        assertEquals(listOf(complete, partial), cancelled.legs)
        assertEquals(1, cancelled.scoredCompleted)
        assertFalse(model.state.value.running)
        runCurrent()
        assertEquals(1, calls)

        model.runComparison()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(model.state.value.running)
        model.cancel()
        runCurrent()
    }

    @Test fun anEditedOrRemovedSelectedProfileStopsTheRunWithoutRetargetingItsResults() = runTest {
        val profiles = MutableStateFlow(listOf(remote, local))
        val captured = mutableListOf<PlatformV2>()
        val complete = completedLeg()
        val model = model(profiles) { profile, runId, onUpdate ->
            captured += profile
            onUpdate(PrivacyComparisonResult(runId, legs = listOf(complete)))
            awaitCancellation()
        }
        runCurrent()
        model.selectProfile(remote.uid)
        model.runComparison()
        runCurrent()
        profiles.value = listOf(remote.copy(model = "edited-model"), local)
        runCurrent()
        assertEquals(PrivacyComparisonStatus.INCOMPLETE, model.state.value.result?.status)
        assertEquals(PrivacyComparisonFailure.PROFILE_CHANGED, model.state.value.result?.failure)
        assertEquals(listOf(complete), model.state.value.result?.legs)
        assertEquals(remote.model, model.state.value.runProfile?.model)
        assertEquals(listOf(remote), captured)
        assertFalse(model.state.value.running)

        model.runComparison()
        runCurrent()
        assertEquals("edited-model", captured.last().model)
        profiles.value = listOf(local)
        runCurrent()
        assertNull(model.state.value.selectedUid)
        assertEquals(PrivacyComparisonFailure.PROFILE_CHANGED, model.state.value.result?.failure)
        assertEquals(2, captured.size)
        model.runComparison()
        runCurrent()
        assertEquals(2, captured.size)
    }

    @Test fun initializationFailureKeepsOnlyTheFiniteFailureCodeAndRequiresAnotherExplicitRun() = runTest {
        var calls = 0
        val model = model(MutableStateFlow(listOf(remote))) { _, _, _ ->
            calls++
            error("SYNTHETIC_FAILURE_DETAIL")
        }
        runCurrent()
        model.selectProfile(remote.uid)
        model.runComparison()
        runCurrent()
        assertFalse(model.state.value.running)
        assertEquals(PrivacyComparisonStatus.INCOMPLETE, model.state.value.result?.status)
        assertEquals(PrivacyComparisonFailure.INITIALIZATION_FAILED, model.state.value.result?.failure)
        assertFalse(model.state.value.toString().contains("SYNTHETIC_FAILURE_DETAIL"))
        runCurrent()
        assertEquals(1, calls)
        model.runComparison()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun cancellingBeforeTheScheduledCoroutineStartsLeavesTheControlsReadyWithoutAnyRequest() = runTest {
        var calls = 0
        val model = model(MutableStateFlow(listOf(remote))) { _, _, _ ->
            calls++
            awaitCancellation()
        }
        runCurrent()
        model.selectProfile(remote.uid)
        model.runComparison()
        model.cancel()
        runCurrent()
        assertEquals(0, calls)
        assertFalse(model.state.value.running)
        assertEquals(PrivacyComparisonStatus.CANCELLED, model.state.value.result?.status)
        assertEquals(PrivacyComparisonFailure.CANCELLED, model.state.value.result?.failure)

        model.runComparison()
        runCurrent()
        assertEquals(1, calls)
        model.cancel()
        runCurrent()
    }

    @Test fun batchProfilesShowTheEffectiveSynchronousModelWhileTheBoundSavedProfileStaysUnchanged() = runTest {
        val batch = remote.copy(compatibleType = ClientType.OPENROUTER, model = "fictional-model:batch", batchMode = true)
        var captured: PlatformV2? = null
        val model = model(MutableStateFlow(listOf(batch))) { profile, _, _ ->
            captured = profile
            awaitCancellation()
        }
        runCurrent()
        model.selectProfile(batch.uid)
        assertEquals(batch.model, model.state.value.selectedProfile?.model)
        model.runComparison()
        runCurrent()
        assertEquals(batch, captured)
        assertEquals("fictional-model", model.state.value.runProfile?.model)
        assertEquals(privacyComparisonProfile(batch).model, model.state.value.runProfile?.model)
        assertEquals("fictional-model:batch", captured?.model)
        assertTrue(captured?.batchMode == true)
        model.cancel()
        runCurrent()
    }

    private fun model(
        profiles: MutableStateFlow<List<PlatformV2>>,
        compare: suspend (PlatformV2, String, (PrivacyComparisonResult) -> Unit) -> PrivacyComparisonResult
    ): PrivacyComparisonViewModel = PrivacyComparisonViewModel(profiles, compare).also(models::add)

    private fun completedLeg() = PrivacyComparisonLeg(
        caseId = PrivacyComparisonCase.TRAVEL.id,
        arm = PrivacyComparisonArm.OFF,
        status = PrivacyComparisonLegStatus.COMPLETE,
        answer = "Synthetic complete answer",
        criteria = listOf(PrivacyComparisonCriterion("synthetic-check", "Synthetic criterion", true))
    )
}
