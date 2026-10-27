package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.bindAuthority
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredDispatchAuthorityTest {
    @Test fun outputIncreasesAreHarmlessButDecreasesAndCredentialChangesRequireReview() {
        val before = PlatformV2(uid = "p", name = "P", model = "model", maxTokens = 1024, token = "secret")
        assertTrue(ConfiguredDispatchAuthority.allowsAcceptedProfile(before, before.copy(maxTokens = 4096, name = "Renamed")))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedProfile(before, before.copy(maxTokens = 512)))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedProfile(before, before.copy(maxTokens = 4096, token = "changed")))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedProfile(before.copy(maxTokens = null), before))
    }

    @Test
    fun savedAuthorityRetainsTheAdmittedModelWhileRevalidatingTheRegisteredProfile() = runTest {
        val settings = mockk<SettingRepository>()
        val registered = PlatformV2(uid = "writer", name = "Writer", model = "default-model")
        val admitted = registered.copy(model = "conversation-model")
        var accepted = true
        coEvery { settings.fetchPlatformV2s() } returns listOf(registered)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { settings.providerActivationRequired(any()) } answers { firstArg<PlatformV2>().model != admitted.model || !accepted }
        coEvery { settings.providerPermissionRevision(any()) } answers { firstArg<PlatformV2>().model + ":" + accepted }
        val guard = ConfiguredDispatchAuthority.capture(settings, admitted, 7)
        val captured = requireNotNull(guard.authority)
        captured.validate(settings)
        accepted = false
        assertTrue(runCatching { captured.validate(settings) }.isFailure)
    }

    @Test
    fun personalEvidenceAddedAfterPreparationBlocksThePhysicalBoundaryWithoutCharging() = runTest {
        val settings = mockk<SettingRepository>()
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.OVHCLOUD
        val profile = PlatformV2(uid = "writer", name = "Writer", apiUrl = provider.apiUrl, model = provider.model, compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.FREE)
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "accepted-activation-only"
        val guard = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        val evidence = dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext(7, null)
        evidence.add(
            listOf(
                dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance(
                    sourceToolId = "memory",
                    sourceProfileUid = "reader",
                    sourceRef = "cached-result",
                    categories = setOf(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory.MEMORY)
                )
            )
        )
        var charged = false
        val failure = withContext(guard + evidence + PhysicalAttemptBoundary { charged = true }) {
            runCatching { PhysicalAttemptBoundary.dispatch() }.exceptionOrNull()
        }
        assertTrue(failure?.message.orEmpty().startsWith("PERSONAL_DATA_PERMISSION_REQUIRED"))
        assertFalse(charged)
    }

    @Test
    fun providerWaitSnapshotRetainsInheritedAuthorityWithoutCredentials() = runTest {
        val settings = mockk<SettingRepository>()
        val primary = PlatformV2(uid = "primary", name = "Primary", model = "writer", token = "primary-secret")
        val helper = PlatformV2(uid = "helper", name = "Helper", model = "reviewer", token = "helper-secret")
        var parentPermission = "accepted-parent"
        coEvery { settings.fetchPlatformV2s() } returns listOf(primary, helper)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } answers { if (firstArg<PlatformV2>().uid == primary.uid) parentPermission else "accepted-helper" }
        val parent = ConfiguredDispatchAuthority.capture(settings, primary, 7)
        val child = withContext(parent) { ConfiguredDispatchAuthority.capture(settings, helper, 7) }
        val decision = ProviderLimitDecision("TEST", helper.uid, helper.model, 1000L, "scope", "PROVIDER_RATE_LIMITED", true).bindAuthority(child)
        val serialized = Json.encodeToString(decision)
        val restored = Json.decodeFromString<ProviderLimitDecision>(serialized)
        assertEquals(decision, restored)
        assertFalse(serialized.contains("primary-secret") || serialized.contains("helper-secret"))
        val captured = requireNotNull(restored.authority)
        assertEquals(primary.uid, captured.parents.single().profileUid)
        captured.validate(settings)
        parentPermission = "revoked-parent"
        assertTrue(runCatching { captured.validate(settings) }.isFailure)
        assertTrue(runCatching { decision.bindAuthority(child) }.isFailure)
    }

    @Test
    fun reviewerRoleChangeRevokesAuthorityButResearchCardDisplayDoesNot() {
        val initial = AppFeatureSettings()
        val renamedDisplay = initial.copy(researchFeature = initial.researchFeature.copy(articleCards = false, compactCards = false))
        assertEquals(ConfiguredDispatchAuthority.policyRevision(initial, "p", 1), ConfiguredDispatchAuthority.policyRevision(renamedDisplay, "p", 1))
        val reviewer = initial.copy(researchFeature = initial.researchFeature.copy(reviewerProfileUid = "new-reviewer"))
        assertFalse(ConfiguredDispatchAuthority.policyRevision(initial, "p", 1) == ConfiguredDispatchAuthority.policyRevision(reviewer, "p", 1))
    }

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
    fun crawlerSelectionDoesNotRevokeWriterButResearchScopeAndDepthStillDo() {
        val selection = dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(researchToggle = true, depth = 50, crawlerProfileUid = "old")
        val initial = AppFeatureSettings(conversationResearch = mapOf(4 to selection))
        val changedCrawler = initial.copy(conversationResearch = mapOf(4 to selection.copy(crawlerProfileUid = "new")))
        assertTrue(ConfiguredDispatchAuthority.policyRevision(initial, "writer", 4) == ConfiguredDispatchAuthority.policyRevision(changedCrawler, "writer", 4))
        for (changed in listOf(selection.copy(depth = 0), selection.copy(scope = dev.chungjungsoo.gptmobile.data.model.ResearchContentScope.WEB_ONLY), selection.copy(researchToggle = false))) {
            assertFalse(ConfiguredDispatchAuthority.policyRevision(initial, "writer", 4) == ConfiguredDispatchAuthority.policyRevision(initial.copy(conversationResearch = mapOf(4 to changed)), "writer", 4))
        }
    }

    @Test
    fun policyCaptureRetriesOnlyPreparationWhenRevisionSettles() = runTest {
        val settings = mockk<SettingRepository>()
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "initial"
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
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "initial"
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
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "initial"
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
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "initial"
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

    @Test
    fun restoredWaitAuthorityRemainsInheritedAfterFreshProviderPreparation() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(id = 3, uid = "writer", name = "Writer")
        var permission = "accepted-wait"
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } answers { permission }
        val saved = requireNotNull(ConfiguredDispatchAuthority.capture(settings, profile, 1).authority)
        val restored = Json.decodeFromString<CapturedProviderAuthority>(Json.encodeToString(saved))
        val prepared = withContext(restored.requestGuard(settings)) {
            ConfiguredDispatchAuthority.capture(settings, profile, 1)
        }
        assertEquals(restored, prepared.authority!!.parents.single())
        permission = "revoked-after-preparation"
        var charged = false
        val rejected = withContext(prepared + PhysicalAttemptBoundary { charged = true }) {
            runCatching { PhysicalAttemptBoundary.dispatch() }.exceptionOrNull()
        }
        assertTrue(rejected is ProviderWaitAuthorityChanged)
        assertFalse(charged)
    }

    @Test
    fun consentRevocationAfterPreparationBlocksDispatchWithoutCharging() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(uid = "profile", name = "Primary")
        var permission = "accepted"
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } answers { permission }
        coEvery { settings.fetchPlatformV2s() } returns emptyList()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        val guard = ConfiguredDispatchAuthority.capture(settings, profile)
        permission = "revoked"
        var charged = false
        val result = withContext(guard + PhysicalAttemptBoundary { charged = true }) {
            runCatching { PhysicalAttemptBoundary.dispatch() }
        }
        assertTrue(result.isFailure)
        assertFalse(charged)
    }

    @Test fun anotherContributorsQuoteAndContextCeilingDoNotInvalidateTheWriter() {
        val price = dev.chungjungsoo.gptmobile.data.accounting.ModelPrice("model", 10, 20, "catalog", 1000)
        val initial = AppFeatureSettings(spendBudget = dev.chungjungsoo.gptmobile.data.accounting.SpendBudgetSettings(perTurnMicros = 100))
        val updated = initial.copy(
            spendBudget = initial.spendBudget.copy(prices = mapOf("other" to price)),
            tokenBudget = initial.tokenBudget.copy(profileContextCeilings = mapOf("other" to 4096))
        )
        assertEquals(ConfiguredDispatchAuthority.policyRevision(initial, "writer", 4), ConfiguredDispatchAuthority.policyRevision(updated, "writer", 4))
        assertFalse(ConfiguredDispatchAuthority.policyRevision(initial, "other", 4) == ConfiguredDispatchAuthority.policyRevision(updated, "other", 4))
    }

    @Test fun increasedTokenCapsPreserveAcceptedPlanButReductionsAndToolCatalogChangesDoNot() {
        val before = TokenBudgetSettings(contextTokens = 4096, outputTokens = 512, totalRunTokens = 8192)
        assertTrue(ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(before, before.copy(contextTokens = 8192, outputTokens = 1024), "writer"))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(before.copy(responseCharacterLimit = 0), before.copy(responseCharacterLimit = 4096), "writer"))
        assertTrue(ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(before, before.copy(generationTimeoutMinutes = 0, responseCharacterLimit = 0), "writer"))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(before, before.copy(outputTokens = 256), "writer"))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(before, before.copy(adaptiveToolCatalog = !before.adaptiveToolCatalog), "writer"))
        assertFalse(ConfiguredDispatchAuthority.allowsAcceptedTokenPlan(before, before.copy(profileContextCeilings = mapOf("writer" to 1024)), "writer"))
    }
}
