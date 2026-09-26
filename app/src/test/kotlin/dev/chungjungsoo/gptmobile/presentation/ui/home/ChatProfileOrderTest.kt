package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatProfileOrderTest {
    @Test
    fun favoritesKeepOriginalSelectionIndicesAndDisabledProfilesStayHidden() {
        val profiles = listOf(
            PlatformV2(name = "Regular"),
            PlatformV2(name = "Paused favorite", enabled = false, isFavorite = true),
            PlatformV2(name = "Favorite A", isFavorite = true),
            PlatformV2(name = "Favorite B", isFavorite = true),
            PlatformV2(name = "Other")
        )
        assertEquals(listOf(2, 3, 0, 4), orderedChatProfiles(profiles).map { it.first })
        assertEquals(listOf(0, 3, 4), orderedChatProfiles(profiles.mapIndexed { i, p -> if (i == 2) p.copy(enabled = false) else p.copy(isFavorite = false) }).map { it.first })
    }
}
