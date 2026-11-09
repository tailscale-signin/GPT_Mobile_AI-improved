package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayChatProfilesTest {
    private val profile = PlatformV2(uid = "gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.4:8090", model = "chosen-model")
    private val revision = "a".repeat(64)
    private val connected = GatewayMenuConnection(
        profile.uid,
        profile.name,
        "192.168.1.4:8090",
        GatewayClientDiagnostics.routeIdentity(profile.engineeringConfig()),
        GatewayAvailability(status = GatewayAvailabilityStatus.ONLINE, instanceId = "instance", conversationSyncSupported = true),
        model = profile.model,
        configurationRevision = revision
    )

    private fun choices(connection: GatewayMenuConnection? = null) = GatewayChatProfiles.choices(listOf(profile), setOf(profile.uid), listOfNotNull(connection), mapOf(profile.uid to revision))

    @Test fun onlyTheExactDirectModelAndSavedConfigurationCanDisplayOnline() {
        assertEquals(GatewayAvailabilityStatus.ONLINE, choices(connected).single().status)
        assertEquals(GatewayAvailabilityStatus.CHECKING, choices(connected.copy(model = "engineering-model")).single().status)
        assertEquals(GatewayAvailabilityStatus.CHECKING, choices(connected.copy(routeIdentity = "another-route")).single().status)
        assertEquals(GatewayAvailabilityStatus.CHECKING, choices(connected.copy(configurationRevision = "b".repeat(64))).single().status)
    }

    @Test fun knownOfflineProfileRetainsItsExactChoiceWithoutClaimingReadiness() {
        assertEquals(GatewayAvailabilityStatus.CHECKING, choices().single().status)
        val offline = connected.copy(availability = GatewayAvailability(status = GatewayAvailabilityStatus.OFFLINE))
        assertEquals(GatewayAvailabilityStatus.OFFLINE, choices(offline).single().status)
        assertEquals(profile.model, choices(offline).single().selection.model)
        assertEquals(profile.apiUrl, choices(offline).single().selection.endpoint)
    }

    @Test fun currentUnsupportedContractCannotBeOverriddenByRememberedSupport() {
        assertTrue(choices(connected.copy(availability = connected.availability.copy(conversationSyncSupported = false))).isEmpty())
        assertTrue(choices(connected.copy(availability = GatewayAvailability(status = GatewayAvailabilityStatus.NOT_GATEWAY))).isEmpty())
        assertTrue(choices(connected.copy(availability = GatewayAvailability(status = GatewayAvailabilityStatus.INCOMPATIBLE))).isEmpty())
    }
}
