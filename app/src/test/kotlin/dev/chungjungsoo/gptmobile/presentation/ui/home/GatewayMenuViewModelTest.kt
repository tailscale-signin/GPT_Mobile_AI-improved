package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayKnownProfileStore
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScope
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityTarget
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayMenuViewModelTest {
    private val profile = PlatformV2(uid = "saved-gateway", name = "Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://10.0.0.2:8090", model = "saved-model")
    private val scope = GatewayVerifiedScope("a".repeat(64), "installation", "account", 1234)
    private val settings = mockk<SettingRepository>()
    private val availability = mockk<GatewayAvailabilityRepository>()
    private val scopes = mockk<GatewayVerifiedScopeStore>()
    private val profiles = MutableStateFlow(listOf(profile))
    private val changes = MutableStateFlow(0L)

    init {
        every { settings.observePlatformV2s() } returns profiles
        every { settings.observeFeatureSettings() } returns flowOf(AppFeatureSettings())
        every { scopes.changes } returns changes
        coEvery { scopes.verified(any()) } returns scope
        coEvery { scopes.configurationRevision(any()) } returns scope.configurationRevision
        every { availability.observeBackground(any()) } answers {
            flowOf(firstArg<List<GatewayAvailabilityTarget>>().associate { it.profileUid to GatewayAvailability(status = GatewayAvailabilityStatus.OFFLINE) })
        }
    }

    @Test fun theGatewayEntrySurvivesDisconnectWithoutClaimingFreshOnlineReadiness() = runTest {
        val connection = gatewayMenuConnections(settings, availability, scopes).first().single()
        assertTrue(connection.canBrowseHistory)
        assertFalse(connection.availability.isConnected)
        assertFalse(connection.availability.isOnline)
        assertEquals(scope, connection.verifiedScope)
    }

    @Test fun anUnverifiedOfflineConfigurationCannotAuthorizeHistoryOrGeneration() = runTest {
        coEvery { scopes.verified(profile) } returns null
        val connection = gatewayMenuConnections(settings, availability, scopes).first().single()
        assertFalse(connection.canBrowseHistory)
        assertFalse(connection.availability.isOnline)
    }

    @Test fun capabilitySupportIsRememberedWithTheFrozenExactConfigurationRevision() = runTest {
        val known = mockk<GatewayKnownProfileStore>()
        coEvery { known.remember(any(), any(), any(), any()) } returns true
        every { availability.observeBackground(any()) } answers {
            val targets = firstArg<List<GatewayAvailabilityTarget>>()
            assertEquals(scope.configurationRevision, targets.single().config?.profileRevision)
            flowOf(targets.associate { it.profileUid to GatewayAvailability(status = GatewayAvailabilityStatus.NOT_READY, instanceId = "instance", conversationSyncSupported = true) })
        }
        val connection = gatewayMenuConnections(settings, availability, scopes, known).first().single()
        assertEquals(scope.configurationRevision, connection.configurationRevision)
        assertFalse(connection.availability.isOnline)
        coVerify(exactly = 1) { known.remember(profile, "instance", scope.configurationRevision, true) }
    }

    @Test fun incompatibleDiscoveryRevokesOnlyItsCapturedConfigurationProof() = runTest {
        val known = mockk<GatewayKnownProfileStore>()
        coEvery { known.forget(any(), any()) } returns Unit
        every { availability.observeBackground(any()) } answers {
            flowOf(firstArg<List<GatewayAvailabilityTarget>>().associate { it.profileUid to GatewayAvailability(status = GatewayAvailabilityStatus.INCOMPATIBLE) })
        }
        gatewayMenuConnections(settings, availability, scopes, known).first()
        coVerify(exactly = 1) { known.forget(profile, scope.configurationRevision) }
        coVerify(exactly = 0) { known.remember(any(), any(), any(), any()) }
    }

    @Test fun anUnresolvedCredentialRevisionCannotBecomeRememberedGatewaySupport() = runTest {
        val known = mockk<GatewayKnownProfileStore>()
        coEvery { scopes.configurationRevision(profile) } returns null
        every { availability.observeBackground(any()) } answers {
            flowOf(firstArg<List<GatewayAvailabilityTarget>>().associate { it.profileUid to GatewayAvailability(status = GatewayAvailabilityStatus.ONLINE, instanceId = "instance") })
        }
        gatewayMenuConnections(settings, availability, scopes, known).first()
        coVerify(exactly = 0) { known.remember(any(), any(), any(), any()) }
    }

    @Test fun retainedHistoryDoesNotDependOnTheExecutionModelOrEnabledReadinessCandidate() = runTest {
        profiles.value = listOf(profile.copy(model = "", enabled = false))
        val connection = gatewayMenuConnections(settings, availability, scopes).first().single()
        assertTrue(connection.canBrowseHistory)
        assertEquals(profile.uid, connection.profileUid)
        assertEquals(GatewayAvailabilityStatus.CONFIGURATION_REQUIRED, connection.availability.status)
        assertFalse(connection.availability.isOnline)
    }

    @Test fun credentialReplacementRemovesOldTrustAndChangesTheBrowserSelectionIdentity() = runTest {
        val observed = mutableListOf<List<GatewayMenuConnection>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gatewayMenuConnections(settings, availability, scopes).collect { observed += it } }
        val old = observed.last().single()
        val replacement = profile.copy(token = "replacement")
        coEvery { scopes.verified(replacement) } returns null
        profiles.value = listOf(replacement)
        val current = observed.last().single()
        assertFalse(current.canBrowseHistory)
        assertNotEquals(old.navigationIdentity, current.navigationIdentity)
    }

    @Test fun aNewVerifiedPrincipalRefreshesTheSelectionWithoutChangingTheRouteOrOnlineClaim() = runTest {
        val observed = mutableListOf<List<GatewayMenuConnection>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gatewayMenuConnections(settings, availability, scopes).collect { observed += it } }
        val old = observed.last().single()
        coEvery { scopes.verified(profile) } returns scope.copy(principalId = "other-account")
        changes.value++
        val current = observed.last().single()
        assertEquals(old.routeIdentity, current.routeIdentity)
        assertNotEquals(old.navigationIdentity, current.navigationIdentity)
        assertEquals("other-account", current.verifiedScope?.principalId)
        assertTrue(current.canBrowseHistory)
        assertFalse(current.availability.isOnline)
    }
}
