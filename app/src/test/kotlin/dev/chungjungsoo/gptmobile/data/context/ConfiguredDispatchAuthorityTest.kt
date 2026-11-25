package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.bindAuthority
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredDispatchAuthorityTest {
    @Test fun matchingGithubSelectionRetainsTheExistingCaptureAndValidationReads() = runTest {
        val profile = PlatformV2(id = 7, uid = "private-requested-profile", name = "Selected", model = "private-requested-model")
        val settings = selectionSettings({ listOf(profile) }, { selectionFeatures(profile.uid) })
        val authority = requireNotNull(ConfiguredDispatchAuthority.capture(settings, profile, 7).authority)
        assertEquals(profile.uid, authority.profileUid)
        assertEquals(profile.model, authority.model)
        verifySelectionReads(settings, 2)
    }

    @Test fun githubProfileMismatchRetainsTheOriginalReadWithoutRetriesOrLaterSettings() = runTest {
        val profile = PlatformV2(id = 7, uid = "private-requested-profile", name = "Selected", model = "private-requested-model")
        var profiles = listOf(profile)
        var features = selectionFeatures("private-selected-profile")
        val settings = selectionSettings({ profiles }, { features })
        var inheritedValidations = 0
        val failure = withContext(RequestAuthorizationGuard("inherited") { inheritedValidations++ }) {
            selectionFailure(settings, profile)
        }
        val observed = failure.failureFields()
        assertEquals(JsonPrimitive(false), observed["github_selection_profile_match"])
        assertEquals(JsonPrimitive(true), observed["github_selection_registered_profile"])
        assertEquals(JsonPrimitive(true), observed["github_selection_model_match"])
        assertEquals(JsonPrimitive(researchHash(profile.uid)), observed["profile_hash"])
        assertEquals(JsonPrimitive(researchHash(profile.model)), observed["model_hash"])
        assertEquals(JsonPrimitive(researchHash("private-selected-profile")), observed["github_selection_profile_hash"])
        assertEquals(JsonPrimitive(researchHash(profile.model)), observed["github_selection_registered_model_hash"])
        features = selectionFeatures(profile.uid)
        profiles = listOf(profile.copy(model = "later-private-model"))
        assertEquals(observed, failure.failureFields())
        assertEquals(0, inheritedValidations)
        verifySelectionReads(settings, 1)
        for (privateValue in listOf(profile.uid, profile.model, "private-selected-profile", "later-private-model")) {
            assertFalse(failure.message.orEmpty().contains(privateValue))
            assertFalse(observed.toString().contains(privateValue))
        }
    }

    @Test fun githubModelMismatchKeepsRequestedAndRegisteredModelIdentitiesSeparate() = runTest {
        val requested = PlatformV2(id = 7, uid = "selected", name = "Selected", model = "private-requested-model")
        val registered = requested.copy(model = "private-registered-model")
        val settings = selectionSettings({ listOf(registered) }, { selectionFeatures(requested.uid) })
        val failure = selectionFailure(settings, requested)
        assertTrue(failure.profileMatches)
        assertTrue(failure.registeredProfilePresent)
        assertEquals(false, failure.modelMatches)
        assertEquals(researchHash(requested.model), failure.requestedModelHash)
        assertEquals(researchHash(registered.model), failure.registeredModelHash)
        verifySelectionReads(settings, 1)
    }

    @Test fun missingEphemeralGithubProfileDoesNotInventAModelComparison() = runTest {
        val requested = PlatformV2(id = 0, uid = "ephemeral", name = "Ephemeral", model = "private-requested-model")
        val settings = selectionSettings({ emptyList() }, { selectionFeatures(requested.uid) })
        val failure = selectionFailure(settings, requested)
        assertTrue(failure.profileMatches)
        assertFalse(failure.registeredProfilePresent)
        assertNull(failure.modelMatches)
        assertNull(failure.registeredModelHash)
        assertFalse("github_selection_model_match" in failure.failureFields())
        assertFalse("github_selection_registered_model_hash" in failure.failureFields())
        verifySelectionReads(settings, 1)
    }

    @Test fun missingPersistedProfileKeepsTheEarlierRemovalGuardWithoutSelectionEvidence() = runTest {
        val requested = PlatformV2(id = 7, uid = "removed", name = "Removed", model = "model")
        val settings = selectionSettings({ emptyList() }, { selectionFeatures("other") })
        val failure = requireNotNull(runCatching { ConfiguredDispatchAuthority.capture(settings, requested, 7) }.exceptionOrNull())
        assertFalse(failure is GitHubProfileSelectionRejected)
        assertTrue(failure.message.orEmpty().startsWith("PROFILE_REMOVED:"))
        verifySelectionReads(settings, 1, featureReads = 0)
    }

    @Test fun earlierProfileAndPrivacyGuardsDoNotReadOrMisclassifyGithubSelection() = runTest {
        val requested = PlatformV2(id = 7, uid = "selected", name = "Selected", model = "model", token = "private-token")
        for ((registered, expected) in listOf(
            listOf(requested, requested.copy(name = "Duplicate")) to "PROFILE_IDENTITY_AMBIGUOUS:",
            listOf(requested.copy(enabled = false)) to "PROFILE_DISABLED:",
            listOf(requested.copy(token = "private-replacement-token")) to "PROFILE_CONFIGURATION_CHANGED:"
        )) {
            val settings = selectionSettings({ registered }, { selectionFeatures("other") })
            val failure = requireNotNull(runCatching { ConfiguredDispatchAuthority.capture(settings, requested, 7) }.exceptionOrNull())
            assertFalse(failure is GitHubProfileSelectionRejected)
            assertTrue(expected, failure.message.orEmpty().startsWith(expected))
            verifySelectionReads(settings, 1, featureReads = 0)
        }
        val privacy = selectionSettings({ listOf(requested) }, { selectionFeatures("other") })
        coEvery { privacy.providerActivationRequired(any()) } returns true
        val failure = requireNotNull(runCatching { ConfiguredDispatchAuthority.capture(privacy, requested, 7) }.exceptionOrNull())
        assertFalse(failure is GitHubProfileSelectionRejected)
        assertTrue(failure.message.orEmpty().startsWith("PROVIDER_ACTIVATION_REQUIRED:"))
        coVerify(exactly = 1) { privacy.providerActivationRequired(any()) }
        coVerify(exactly = 0) { privacy.providerPermissionRevision(any()) }
        coVerify(exactly = 0) { privacy.fetchPlatformV2s() }
        coVerify(exactly = 0) { privacy.getFeatureSettings() }
    }

    @Test fun nonGithubEphemeralProfileKeepsItsExistingAuthorizationBehavior() = runTest {
        val requested = PlatformV2(id = 0, uid = "ephemeral", name = "Ephemeral", model = "model")
        val settings = selectionSettings({ emptyList() }, { AppFeatureSettings() })
        assertNull(ConfiguredDispatchAuthority.capture(settings, requested, 7).authority)
        verifySelectionReads(settings, 2)
    }

    @Test fun configurationReadCancellationIsNotRetriedOrConvertedIntoSelectionFailure() = runTest {
        val requested = PlatformV2(id = 7, uid = "selected", name = "Selected", model = "model")
        val settings = selectionSettings({ listOf(requested) }, { selectionFeatures("other") })
        val cancelled = CancellationException("The configuration read was cancelled")
        coEvery { settings.fetchPlatformV2s() } throws cancelled
        assertSame(cancelled, runCatching { ConfiguredDispatchAuthority.capture(settings, requested, 7) }.exceptionOrNull())
        verifySelectionReads(settings, 1, featureReads = 0)
    }

    @Test fun behaviorDefaultsAndCollectionOrderPreserveDeliveryWhileRealPolicyChangesStayBlocked() = runTest {
        val profile = PlatformV2(id = 7, uid = "private-profile", name = "Selected", model = "private-model")
        var features = selectionFeatures(profile.uid)
        val settings = selectionSettings({ listOf(profile) }, { features })
        val implicit = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        val oldRevision = researchHash(JsonObject(ConfiguredDispatchAuthority.policyFields(features, profile.uid, 7) + ("profile" to kotlinx.serialization.json.JsonNull)).toString())
        assertTrue(ConfiguredDispatchAuthority.matchesPolicyRevision(oldRevision, features, profile.uid, 7))
        val defaults = ProfileBehaviorSettings()
        features = features.copy(profileBehavior = mapOf(profile.uid to defaults))
        implicit.validate()
        assertTrue(ConfiguredDispatchAuthority.matchesPolicyRevision(oldRevision, features, profile.uid, 7))
        val ordered = defaults.copy(crawlerToolIds = linkedSetOf("private-b", "private-a"), toolPluginStates = linkedMapOf("private-b" to true, "private-a" to false))
        features = features.copy(profileBehavior = mapOf(profile.uid to ordered))
        val guard = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        val reordered = ordered.copy(crawlerToolIds = linkedSetOf("private-a", "private-b"), toolPluginStates = linkedMapOf("private-a" to false, "private-b" to true))
        features = features.copy(profileBehavior = mapOf(profile.uid to reordered))
        guard.validate()
        features = features.copy(profileBehavior = mapOf(profile.uid to reordered.copy(crawlersEnabled = true)))
        val failure = runCatching { guard.validate() }.exceptionOrNull() as DispatchPolicyChangedException
        assertEquals(listOf("profile", "profileBehavior.crawlersEnabled"), failure.fields)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    private fun selectionFeatures(selectedUid: String) = AppFeatureSettings(
        conversationGitHub = mapOf(7 to GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), selectedUid))
    )

    private fun selectionSettings(profiles: () -> List<PlatformV2>, features: () -> AppFeatureSettings): SettingRepository = mockk<SettingRepository>().also { settings ->
        coEvery { settings.fetchPlatformV2s() } answers { profiles() }
        coEvery { settings.getFeatureSettings() } answers { features() }
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "accepted"
    }

    private suspend fun selectionFailure(settings: SettingRepository, requested: PlatformV2): GitHubProfileSelectionRejected {
        val failure = runCatching { ConfiguredDispatchAuthority.capture(settings, requested, 7) }.exceptionOrNull()
        assertTrue("Expected the actual typed selection guard, got $failure", failure is GitHubProfileSelectionRejected)
        return failure as GitHubProfileSelectionRejected
    }

    private fun verifySelectionReads(settings: SettingRepository, reads: Int, featureReads: Int = reads) {
        coVerify(exactly = reads) { settings.providerActivationRequired(any()) }
        coVerify(exactly = reads) { settings.providerPermissionRevision(any()) }
        coVerify(exactly = reads) { settings.fetchPlatformV2s() }
        coVerify(exactly = featureReads) { settings.getFeatureSettings() }
    }

    @Test fun gatewaySelectionReportsOnlyTheDirectionForTheSameProfile() {
        fun fields(uid: String?, profile: String = "same") = kotlinx.serialization.json.buildJsonObject {
            put("githubProfileSelection", JsonPrimitive(profile))
            put("githubProfileOverride", JsonObject(uid?.let { mapOf("engineeringGatewayProfileUid" to JsonPrimitive(it)) }.orEmpty()))
        }
        val a = fields("private-a")
        val b = fields("private-b")
        assertEquals("SELECTED", ConfiguredDispatchAuthority.gatewaySelectionChange(fields(null), a))
        assertEquals("CLEARED", ConfiguredDispatchAuthority.gatewaySelectionChange(a, fields(null)))
        assertEquals("REPLACED", ConfiguredDispatchAuthority.gatewaySelectionChange(a, b))
        assertNull(ConfiguredDispatchAuthority.gatewaySelectionChange(a, a))
        assertNull(ConfiguredDispatchAuthority.gatewaySelectionChange(a, fields("private-b", "different")))
    }

    @Test fun githubPolicyDiagnosticsNameOnlyChangedKnownSubfields() {
        fun fields(value: String) = Json.parseToJsonElement(value) as JsonObject
        val before = fields("""{"githubProfileOverride":{"githubTools":true,"engineeringGatewayProfileUid":"private-old"}}""")
        val after = fields("""{"githubProfileOverride":{"githubTools":false,"engineeringGatewayProfileUid":"private-new","private-key":"secret"}}""")
        assertEquals(listOf("githubProfileOverride.githubTools", "githubProfileOverride.engineeringGatewayProfileUid"), ConfiguredDispatchAuthority.githubOverrideChanges(before, after))
        assertTrue(ConfiguredDispatchAuthority.githubOverrideChanges(before, before).isEmpty())
    }

    @Test fun raisingOnlyIntelligenceKeepsAnAlreadyAcceptedLowerEffortTurnAdmitted() = runTest {
        val profile = PlatformV2(uid = "profile", name = "Selected", model = "model")
        val balanced = dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride()
        val deep = balanced.copy(intelligenceLevel = dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.DEEP_ENGINEERING)
        val initial = selectionFeatures(profile.uid)
        var features = initial
        val settings = selectionSettings({ listOf(profile) }, { features })
        val guard = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        features = initial.copy(githubProfileOverrides = mapOf(profile.uid to deep))
        guard.validate()
        val deeperTurn = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        features = initial.copy(githubProfileOverrides = mapOf(profile.uid to balanced))
        assertTrue(runCatching { deeperTurn.validate() }.exceptionOrNull() is DispatchPolicyChangedException)
        features = initial.copy(githubProfileOverrides = mapOf(profile.uid to deep.copy(githubTools = false)))
        assertTrue(runCatching { guard.validate() }.exceptionOrNull() is DispatchPolicyChangedException)
    }

    @Test fun queuedAndRestoredGithubTasksKeepTheirSubmittedPromptAndRevalidateExecutionSettings() = runTest {
        val profile = PlatformV2(uid = "profile", name = "Selected", model = "model")
        val shortcut = dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow("shortcut", "Label", "First prompt")
        val selected = dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride(quickWorkflows = listOf(shortcut))
        val plugin = dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings(githubCopilotEnabled = true)
        val initial = selectionFeatures(profile.uid).copy(
            githubProfileOverrides = mapOf(profile.uid to selected),
            pluginExecution = linkedMapOf("z" to plugin, ToolPluginId.GITHUB to plugin)
        )
        var features = initial
        val settings = selectionSettings({ listOf(profile) }, { features })
        val guard = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        val saved = requireNotNull(guard.authority)
        val legacy = saved.copy(policyRevision = researchHash(ConfiguredDispatchAuthority.policyFields(features, profile.uid, 7, legacy = true).toString()))
        legacy.validate(settings)

        features = initial.copy(
            githubProfileOverrides = mapOf(profile.uid to selected.copy(quickWorkflows = listOf(shortcut.copy(prompt = "Future task")))),
            pluginExecution = initial.pluginExecution.entries.reversed().associate { it.toPair() }
        )
        guard.validate()
        saved.validate(settings)
        val harmless = features
        val fence = PreparationReplayFence().apply { dispatched() }
        for ((field, changed) in listOf(
            "githubProfileOverride" to harmless.copy(githubProfileOverrides = mapOf(profile.uid to selected.copy(githubTools = false))),
            "plugins" to harmless.copy(pluginExecution = harmless.pluginExecution + (ToolPluginId.GITHUB to plugin.copy(githubCopilotEnabled = false)))
        )) {
            features = changed
            val stale = runCatching { guard.validate() }.exceptionOrNull() as DispatchPolicyChangedException
            assertTrue(field in stale.fields)
            assertFalse(fence.canRebuild(stale, 0))
            assertFalse(fence.canResumeGitHubChecklist(stale, 0, true))
            assertTrue(runCatching { saved.validate(settings) }.exceptionOrNull() is ProviderWaitAuthorityChanged)
            assertTrue(runCatching { legacy.validate(settings) }.exceptionOrNull() is ProviderWaitAuthorityChanged)
        }
    }

    @Test fun githubSelectionChangeRevokesQueuedRequestsAndRejectsAnotherModel() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(uid = "selected", name = "Selected", model = "accepted-model")
        val github = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "selected")
        var features = AppFeatureSettings(conversationGitHub = mapOf(7 to github))
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } answers { features }
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "accepted"
        val guard = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        guard.validate()
        assertTrue(runCatching { ConfiguredDispatchAuthority.capture(settings, profile.copy(model = "substitute"), 7) }.isFailure)
        features = features.copy(conversationGitHub = mapOf(7 to github.copy(mainProfileUid = "different")))
        assertTrue(runCatching { guard.validate() }.isFailure)
        assertTrue(runCatching { guard.authority!!.validate(settings) }.isFailure)
        assertTrue(runCatching { ConfiguredDispatchAuthority.capture(settings, profile, 7) }.isFailure)
    }

    @Test fun policyFieldsSerializeNonDefaultPluginGrantsWithoutSortedMapPolymorphism() {
        val settings = AppFeatureSettings(toolPluginStates = sortedMapOf("second" to false, "first" to false))
        assertEquals("{\"first\":false,\"second\":false}", ConfiguredDispatchAuthority.policyFields(settings, "p", 1).getValue("tools").toString())
    }

    @Test fun explicitToolDefaultsDoNotInvalidateAnActiveRequestButRevocationsDo() {
        val before = AppFeatureSettings()
        val defaults = before.copy(toolPluginStates = mapOf("news" to before.isToolPluginEnabled("news")))
        assertEquals(ConfiguredDispatchAuthority.policyRevision(before, "p", 1), ConfiguredDispatchAuthority.policyRevision(defaults, "p", 1))
        val revoked = before.copy(toolPluginStates = mapOf("news" to !before.isToolPluginEnabled("news")))
        assertFalse(ConfiguredDispatchAuthority.policyRevision(before, "p", 1) == ConfiguredDispatchAuthority.policyRevision(revoked, "p", 1))
    }

    @Test fun voiceChatTogglePreservesActiveAndLegacyGitHubAuthority() = runTest {
        val settings = mockk<SettingRepository>()
        val profile = PlatformV2(uid = "profile", name = "Profile", model = "model")
        val scope = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), profile.uid)
        var features = AppFeatureSettings(conversationGitHub = mapOf(7 to scope))
        coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
        coEvery { settings.getFeatureSettings() } answers { features }
        coEvery { settings.providerActivationRequired(any()) } returns false
        coEvery { settings.providerPermissionRevision(any()) } returns "accepted"
        val guard = ConfiguredDispatchAuthority.capture(settings, profile, 7)
        val authority = requireNotNull(guard.authority)

        features = features.withToolPluginEnabled(ToolPluginId.VOICE_CHAT, true)
        guard.validate()
        authority.validate(settings)

        val oldTools = ToolPluginPolicyProjection.project(features.toolPluginStates, ToolPluginId.optInServices)
        val oldFields = JsonObject(ConfiguredDispatchAuthority.policyFields(features, profile.uid, 7) + ("tools" to oldTools))
        val oldAuthority = authority.copy(policyRevision = researchHash(oldFields.toString()))
        features = features.withToolPluginEnabled(ToolPluginId.VOICE_CHAT, false)
        oldAuthority.validate(settings)

        features = features.withToolPluginEnabled(ToolPluginId.GITHUB, false)
        assertTrue(runCatching { guard.validate() }.exceptionOrNull() is DispatchPolicyChangedException)
        assertTrue(runCatching { oldAuthority.validate(settings) }.exceptionOrNull() is ProviderWaitAuthorityChanged)
    }

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

    @Test fun catalogResumeRequiresDurableChecklistAndOnlyToolPolicyChanges() {
        val fence = PreparationReplayFence()
        fence.dispatched()
        val tools = DispatchPolicyChangedException(listOf("tools"))
        assertFalse(fence.canRebuild(tools, 0))
        assertTrue(fence.canResumeGitHubChecklist(tools, 0, true))
        assertFalse(fence.canResumeGitHubChecklist(tools, 0, false))
        assertFalse(fence.canResumeGitHubChecklist(tools, 3, true))
        assertFalse(fence.canResumeGitHubChecklist(DispatchPolicyChangedException(listOf("tools", "profile.token")), 0, true))
    }
}
