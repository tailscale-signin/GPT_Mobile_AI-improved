package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueInvalidationPolicyTest {
    @Test fun inferenceAndPresentationChangesDoNotDiscardUnchangedToolCatalogues() {
        val profile = PlatformV2(uid = "profile", name = "Profile", apiUrl = "https://provider.example/v1", secretRef = "vault-ref")
        assertFalse(CatalogueInvalidationPolicy.profileChanged(profile, profile.copy(name = "Renamed", isFavorite = true, model = "new-model", temperature = 0.2f, reasoning = true)))
        assertTrue(CatalogueInvalidationPolicy.profileChanged(profile, profile.copy(secretRef = "new-ref")))
        assertTrue(CatalogueInvalidationPolicy.profileChanged(profile, profile.copy(disableRemoteTools = true)))
        assertTrue(CatalogueInvalidationPolicy.profileChanged(profile, profile.copy(enabled = false)))
        assertTrue(CatalogueInvalidationPolicy.profileChanged(profile, profile.copy(providerConnectionUid = "other-connection")))
    }

    @Test fun recentProfileSelectionKeepsCataloguesButToolPermissionChangesInvalidateThem() {
        val settings = AppFeatureSettings()
        assertFalse(CatalogueInvalidationPolicy.featuresChanged(settings, settings.copy(lastChatProfileUids = listOf("profile"))))
        assertTrue(CatalogueInvalidationPolicy.featuresChanged(settings, settings.copy(remoteMcpConnections = !settings.remoteMcpConnections)))
        assertTrue(CatalogueInvalidationPolicy.featuresChanged(settings, settings.copy(toolPluginStates = mapOf("connection:private" to false))))
        assertTrue(CatalogueInvalidationPolicy.featuresChanged(settings, settings.copy(profileBehavior = mapOf("profile" to ProfileBehaviorSettings(toolPluginStates = mapOf("connection:private" to false))))))
    }

    @Test fun presentationAndReasoningEditsRetainCataloguesButClearCacheRemainsExplicit() {
        val settings = AppFeatureSettings()
        val presentation = settings.copy(
            compactChatList = true,
            showReasoning = false,
            diagnosticsCollection = true,
            debugShowNetwork = true,
            conversationReasoning = mapOf(7 to true),
            readiness = settings.readiness.copy(predictMemory = true)
        )
        assertFalse(CatalogueInvalidationPolicy.featuresChanged(settings, presentation))
        assertTrue(CatalogueInvalidationPolicy.featuresChanged(settings, presentation.copy(readiness = presentation.readiness.copy(cacheGeneration = 1))))
    }

    @Test fun scopedPreparationInvalidationCannotAdvanceTheCatalogueAuthorityEpoch() {
        val beforePreparation = ReadinessInvalidation.epoch
        val beforeCatalogue = ReadinessInvalidation.catalogueEpoch
        ReadinessInvalidation.advance(invalidateCatalogues = false)
        assertTrue(ReadinessInvalidation.epoch > beforePreparation)
        assertEquals(beforeCatalogue, ReadinessInvalidation.catalogueEpoch)
        ReadinessInvalidation.advance(reason = "PROFILE_AUTHORITY_CHANGED")
        assertTrue(ReadinessInvalidation.catalogueEpoch > beforeCatalogue)
        assertEquals("PROFILE_AUTHORITY_CHANGED", ReadinessInvalidation.catalogueReason)
    }
}
