package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredDispatchAuthorityTest {
    @Test
    fun priceQuoteRefreshDoesNotRevokeAuthorityButChangedRatesDo() {
        val price = dev.chungjungsoo.gptmobile.data.accounting.ModelPrice("model", 10, 20, "catalog", 1000)
        val initial = AppFeatureSettings(spendBudget = dev.chungjungsoo.gptmobile.data.accounting.SpendBudgetSettings(perTurnMicros = 100, prices = mapOf("profile" to price)))
        val refreshed = initial.copy(spendBudget = initial.spendBudget.copy(prices = mapOf("profile" to price.copy(checkedAt = 2000, source = "refreshed catalog"))))
        assertTrue(ConfiguredDispatchAuthority.policyRevision(initial, "profile", null) == ConfiguredDispatchAuthority.policyRevision(refreshed, "profile", null))
        val repriced = initial.copy(spendBudget = initial.spendBudget.copy(prices = mapOf("profile" to price.copy(outputMicrosPerMillion = 30))))
        assertFalse(ConfiguredDispatchAuthority.policyRevision(initial, "profile", null) == ConfiguredDispatchAuthority.policyRevision(repriced, "profile", null))
    }

    @Test
    fun policyCaptureRetriesOnlyPreparationWhenRevisionSettles() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(uid = "ephemeral", name = "Benchmark")
        coEvery { settings.fetchPlatformV2s() } returns emptyList()
        var reads = 0
        coEvery { settings.getFeatureSettings() } answers {
            reads++
            AppFeatureSettings(remoteMcpConnections = reads == 1)
        }
        val authority = ConfiguredDispatchAuthority.capture(settings, profile)
        authority.validate()
        assertTrue(reads >= 4)
    }

    @Test
    fun queuedProfileCredentialChangeAndDeletionBlockEveryNewPhysicalAttempt() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(id = 3, uid = "profile", name = "Primary", token = "old")
        var profiles = listOf(profile)
        coEvery { settings.fetchPlatformV2s() } answers { profiles }
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        val authority = ConfiguredDispatchAuthority.capture(settings, profile)
        authority.validate()
        profiles = listOf(profile.copy(name = "Renamed", isFavorite = true))
        authority.validate()
        profiles = listOf(profile.copy(token = "new"))
        assertTrue(runCatching { ConfiguredDispatchAuthority.capture(settings, profile) }.isFailure)
        assertTrue(runCatching { authority.validate() }.isFailure)
        profiles = emptyList()
        assertTrue(runCatching { authority.validate() }.isFailure)
        assertTrue(runCatching { ConfiguredDispatchAuthority.capture(settings, profile) }.isFailure)
    }

    @Test
    fun displayAndDeadlineEditsDoNotInvalidateButPermissionChangesDo() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(uid = "ephemeral", name = "Benchmark")
        var features = AppFeatureSettings()
        coEvery { settings.fetchPlatformV2s() } returns emptyList()
        coEvery { settings.getFeatureSettings() } answers { features }
        val authority = ConfiguredDispatchAuthority.capture(settings, profile)
        features = features.copy(debugShowRuntime = false, profileProgressDeadlines = mapOf(profile.uid to dev.chungjungsoo.gptmobile.data.agent.ProgressDeadlineSettings(firstProgressMillis = 1000)))
        authority.validate()
        features = features.copy(remoteMcpConnections = false)
        assertTrue(runCatching { authority.validate() }.isFailure)
    }

    @Test
    fun childRetainsParentSourceAuthorityBeforeItsOwnDispatch() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(uid = "helper", name = "Helper")
        coEvery { settings.fetchPlatformV2s() } returns emptyList()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        var allowed = true
        var charged = false
        val parent = RequestAuthorizationGuard("parent") { check(allowed) }
        val child = withContext(parent) { ConfiguredDispatchAuthority.capture(settings, profile) }
        allowed = false
        val allowance = PhysicalAttemptBoundary { charged = true }
        val rejected = withContext(child + allowance) { runCatching { PhysicalAttemptBoundary.dispatch() }.isFailure }
        assertTrue(rejected)
        assertFalse(charged)
    }
}

