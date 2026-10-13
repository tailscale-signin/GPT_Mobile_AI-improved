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

    @Test fun shortLegacySelectionAndDisableAreSafe() {
        val profiles = listOf(PlatformV2(uid = "a", name = "Fixture"), PlatformV2(uid = "b", name = "Fixture"))
        assertEquals(setOf("a"), selectedProfileUids(profiles, listOf(true)))
        assertEquals(emptyList<String>(), confirmedProfileUids(profiles.map { it.copy(enabled = false) }, setOf("a", "b")))
    }
}
