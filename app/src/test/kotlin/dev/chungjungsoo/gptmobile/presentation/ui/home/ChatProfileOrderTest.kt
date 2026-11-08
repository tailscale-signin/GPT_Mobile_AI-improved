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

    @Test fun onlinePartitionPreservesUserSortAndCannotReintroduceFilteredRows() {
        val favorite = PlatformV2(uid = "favorite", name = "Favorite", isFavorite = true)
        val onlineA = PlatformV2(uid = "a", name = "A")
        val onlineB = PlatformV2(uid = "b", name = "B")
        val input = listOf(favorite, onlineB, onlineA)
        assertEquals(listOf("b", "a", "favorite"), onlineFirstChatProfiles(input, setOf("a", "b", "hidden")).map { it.uid })
        assertEquals(listOf("favorite", "b", "a"), input.map { it.uid })
        assertEquals(listOf("favorite"), onlineFirstChatProfiles(listOf(favorite), setOf("a", "b")).map { it.uid })
    }

    @Test fun statusChangesWaitForInteractionToEndWhileRemovalAndFilterChangesTakeEffect() {
        val first = PlatformV2(uid = "first", name = "First")
        val gateway = PlatformV2(uid = "gateway", name = "Gateway")
        val added = PlatformV2(uid = "added", name = "Added")
        val displayed = listOf("first", "gateway")
        val desired = onlineFirstChatProfiles(listOf(first, gateway), setOf("gateway"))
        assertEquals(displayed, deferredChatProfileOrder(desired, displayed, true).map { it.uid })
        assertEquals(listOf("gateway", "first"), deferredChatProfileOrder(desired, displayed, false).map { it.uid })
        assertEquals(listOf("gateway", "added"), deferredChatProfileOrder(listOf(added, gateway), displayed, true).map { it.uid })
    }
}
