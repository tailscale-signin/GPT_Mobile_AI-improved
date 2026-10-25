package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectPlatformSelectionTest {
    @Test fun hiddenAndFavoriteRowsRetainCanonicalSelectionOrder() {
        val profiles = listOf(PlatformV2(uid = "a", name = "Same"), PlatformV2(uid = "b", name = "Same", isFavorite = true), PlatformV2(uid = "disabled", name = "Fixture", enabled = false))
        val selected = selectedProfileUids(profiles, listOf(true, true, true))
        assertEquals(setOf("a", "b"), selected)
        assertEquals(listOf("b", "a"), orderedChatProfiles(profiles).map { it.second.uid })
        assertEquals(listOf("a", "b"), confirmedProfileUids(profiles, selected))
        assertEquals(listOf("b"), confirmedProfileUids(profiles.drop(1), selected))
    }

    @Test fun savedOpenCodeSelectionIsUnavailableBeforeConfirmationWithoutChangingHistoryIdentity() {
        val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.OPENCODE
        val profile = PlatformV2(uid = "existing-history-owner", name = "Saved OpenCode", compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
        assertEquals(emptySet<String>(), selectedProfileUids(listOf(profile), listOf(true)))
        assertEquals(emptyList<String>(), confirmedProfileUids(listOf(profile), setOf(profile.uid)))
        assertEquals("existing-history-owner", profile.uid)
        assertEquals("PROVIDER_CLIENT_RESTRICTED", dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile)!!.category.name)
    }

    @Test fun shortLegacySelectionAndDisableAreSafe() {
        val profiles = listOf(PlatformV2(uid = "a", name = "Fixture"), PlatformV2(uid = "b", name = "Fixture"))
        assertEquals(setOf("a"), selectedProfileUids(profiles, listOf(true)))
        assertEquals(emptyList<String>(), confirmedProfileUids(profiles.map { it.copy(enabled = false) }, setOf("a", "b")))
    }

    @Test fun temporaryQuotaKeepsSelectionButCannotSilentlyDropASelectedMember() {
        val file = java.nio.file.Files.createTempDirectory("selection-quota").resolve("state.json").toFile()
        val limiter = dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter.shared
        try {
            limiter.initialize(file)
            val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.OVHCLOUD
            val free = PlatformV2(uid = "free", name = "Free", apiUrl = provider.apiUrl, model = provider.model, compatibleType = dev.chungjungsoo.gptmobile.data.model.ClientType.FREE)
            val available = PlatformV2(uid = "available", name = "Available")
            limiter.defer(provider, "5", free.model)
            val profiles = listOf(free, available)
            val selected = selectedProfileUids(profiles, listOf(true, true))
            assertEquals(setOf("free", "available"), selected)
            assertEquals(emptyList<String>(), confirmedProfileUids(profiles, selected))
            assertEquals(listOf("available"), confirmedProfileUids(profiles, setOf("available")))
        } finally {
            file.delete()
            limiter.initialize(file)
            file.parentFile.deleteRecursively()
        }
    }
}
