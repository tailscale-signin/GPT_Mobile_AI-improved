package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSetting
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPending
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlSnapshot
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayUpdateStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h1000dp-mdpi")
class GatewayEngineeringSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val profile = PlatformV2(uid = "selected-pc", name = "Selected PC", apiUrl = "http://100.90.1.2:8090/v1", model = "selected-model")
    private val endpoint = "https://100.90.1.2:8091"
    private val enabled = JsonObject(mapOf("engineering_enabled" to JsonPrimitive(true)))
    private val snapshot = GatewayControlSnapshot(
        schema = "gptmobile.gateway.settings.v1",
        installationId = "installation-selected-pc",
        principalId = "paired:selected-owner",
        revision = 7,
        observedAt = 1.0,
        desired = enabled,
        effective = enabled,
        writableFields = listOf("engineering_enabled"),
        role = "owner"
    )
    private val states = MutableStateFlow(mapOf(profile.uid to GatewayControlState(GatewayControlPhase.LIVE, snapshot, engineeringBindingConfirmed = true, targetEndpoint = endpoint)))
    private val busy = MutableStateFlow(false)
    private val direct = MutableStateFlow<Map<Pair<String, String>, GatewayAdmissionSetting>>(emptyMap())
    private val model = mockk<GatewayControlViewModel>(relaxed = true).also { model ->
        every { model.profiles } returns MutableStateFlow(listOf(profile))
        every { model.states } returns states
        every { model.busy } returns busy
        every { model.error } returns MutableStateFlow(null)
        every { model.direct } returns direct
        every { model.networkOverview } returns MutableStateFlow(emptyMap())
        every { model.modelChoices } returns MutableStateFlow(emptyMap())
        every { model.modelStatus } returns MutableStateFlow(emptyMap())
    }

    private fun showDialog() {
        compose.setContent {
            MaterialTheme { GatewayControlSettingsDialog({}, initialProfileUid = profile.uid, model = model) }
        }
    }

    @Test fun pairedEngineeringIsReadyWithoutAnAdmissionSwitch() {
        showDialog()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Ready for engineering jobs").assertIsDisplayed()
        compose.onNodeWithText("Confirm this phone").assertDoesNotExist()
        verify(exactly = 0) { model.apply(any(), any(), any(), any()) }
    }

    @Test fun unconfirmedOwnerStillRequiresReviewOfTheExactPc() {
        states.value = states.value.mapValues { (_, state) -> state.copy(engineeringBindingConfirmed = false) }
        showDialog()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Ready for engineering jobs").assertDoesNotExist()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("Confirm this phone").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("Confirm this phone").performClick()
        compose.onNode(hasText(endpoint, substring = true) and hasText(snapshot.installationId.takeLast(12), substring = true)).assertIsDisplayed()
        verify(exactly = 0) { model.apply(any(), any(), any(), any()) }
        compose.onNodeWithText("Confirm owner").performClick()
        verify(exactly = 1) { model.apply(profile, snapshot.revision, enabled, confirmEngineering = true) }
        compose.runOnIdle { states.value = states.value.mapValues { (_, state) -> state.copy(engineeringBindingConfirmed = true) } }
        compose.onNodeWithText("Ready for engineering jobs").assertIsDisplayed()
        compose.onNodeWithText("Confirm this phone").assertDoesNotExist()
    }

    @Test fun prerequisiteFailureKeepsRefreshAndUpdateActionsWithoutOffControl() {
        val blocked = snapshot.copy(
            effective = JsonObject(mapOf("engineering_enabled" to JsonPrimitive(false))),
            pending = listOf(GatewayControlPending("engineering_enabled", "Connect GitHub on this PC.")),
            writableFields = listOf("engineering_enabled", "update_mode"),
            updates = GatewayUpdateStatus("18.1.0", "available", "18.1.1")
        )
        states.value = states.value.mapValues { (_, state) -> state.copy(snapshot = blocked) }
        showDialog()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Gateway setup needed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ready for engineering jobs").assertDoesNotExist()
        compose.onNodeWithText("Connect GitHub on this PC.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Refresh confirmed state").performScrollTo().performClick()
        verify(atLeast = 1) { model.refresh(profile) }
        compose.onNodeWithText("Install 18.1.1 when idle").performScrollTo().performClick()
        verify(exactly = 1) { model.updateGateway(profile, "18.1.1") }
        verify(exactly = 0) { model.apply(any(), any(), any(), any()) }
    }

    @Test fun staleRevokedAndViewerSnapshotsCannotClaimReadyOwnerAccess() {
        states.value = states.value.mapValues { (_, state) -> state.copy(phase = GatewayControlPhase.STALE, engineeringBindingConfirmed = false) }
        showDialog()
        compose.onNodeWithText("Ready for engineering jobs").assertDoesNotExist()
        compose.onNodeWithText("Confirm this phone").assertDoesNotExist()
        compose.onNodeWithText("Reconnect").performClick()
        verify(atLeast = 1) { model.refresh(profile) }
        compose.runOnIdle { states.value = states.value.mapValues { (_, state) -> state.copy(phase = GatewayControlPhase.REVOKED) } }
        compose.onNodeWithText("Owner access required").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ready for engineering jobs").assertDoesNotExist()
        compose.onNodeWithText("Confirm this phone").assertDoesNotExist()
        compose.runOnIdle { states.value = states.value.mapValues { (_, state) -> state.copy(phase = GatewayControlPhase.LIVE, snapshot = snapshot.copy(role = "viewer", writableFields = emptyList())) } }
        compose.onNodeWithText("Owner access required").assertIsDisplayed()
        compose.onNodeWithText("Ready for engineering jobs").assertDoesNotExist()
        compose.onNodeWithText("Confirm this phone").assertDoesNotExist()
        verify(exactly = 0) { model.apply(any(), any(), any(), any()) }
    }

    @Test fun legacyAccountShowsReadinessAndRefreshWithoutToggle() {
        states.value = mapOf(profile.uid to GatewayControlState(GatewayControlPhase.UNPAIRED))
        val admission = GatewayAdmissionSetting(false, 7, "selected-pc", "tailnet:selected-owner")
        direct.value = mapOf((profile.uid to profile.apiUrl) to admission)
        showDialog()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Gateway setup needed").assertIsDisplayed()
        compose.onNodeWithText("Refresh readiness").performClick()
        verify(exactly = 1) { model.refresh(profile, forceNetwork = true) }
        compose.runOnIdle { direct.value = mapOf((profile.uid to profile.apiUrl) to admission.copy(enabled = true)) }
        compose.onNodeWithText("Ready for engineering jobs").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        verify(exactly = 0) { model.apply(any(), any(), any(), any()) }
    }

    @Test fun profileAdmissionRefreshNeverSubmitsAnAdmissionMutation() {
        val requests = mutableListOf<GatewayAdmissionSetting?>()
        compose.setContent {
            MaterialTheme {
                Column {
                    GatewayAdmissionControl { previous ->
                        requests += previous
                        GatewayAdmissionSetting(requests.size == 1, 7, "selected-pc", "tailnet:selected-owner")
                    }
                }
            }
        }
        compose.onNodeWithText("Check engineering readiness").performClick()
        compose.onNodeWithText("Ready for engineering jobs").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Refresh readiness").performClick()
        compose.onNodeWithText("Gateway setup needed").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, requests.size)
            assertTrue(requests.all { it == null })
        }
    }
}
