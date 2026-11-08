package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayProfileOrderTest {
    private fun promoted(base: List<String>, online: Set<String>): List<String> = onlineFirstChatProfiles(base.map { PlatformV2(uid = it, name = it) }, online).map { it.uid }
    private fun retained(previous: List<String>, desired: List<String>, interacting: Boolean): List<String> = deferredChatProfileOrder(desired.map { PlatformV2(uid = it, name = it) }, previous, interacting).map { it.uid }

    @Test fun promotionPreservesTheChosenSortWithinBothGroups() {
        val base = listOf("alpha", "bravo", "charlie", "delta")
        assertEquals(listOf("bravo", "delta", "alpha", "charlie"), promoted(base, setOf("delta", "bravo", "filtered-out")))
        assertEquals(base, promoted(base, emptySet()))
    }

    @Test fun freshAvailabilityPrecedesFavoritesAndSelectionIdentityNeverUsesRowPosition() {
        val base = listOf("favorite-a", "favorite-b", "other-a", "other-b")
        val selection = setOf("favorite-a", "other-b")
        val ordered = promoted(base, setOf("favorite-b", "other-a"))
        assertEquals(listOf("favorite-b", "other-a", "favorite-a", "other-b"), ordered)
        assertEquals(selection, ordered.filter { it in selection }.toSet())
    }

    @Test fun touchScrollAndAccessibilityHoldExistingTargetsUntilInteractionEnds() {
        val before = listOf("a", "b", "c")
        val promoted = listOf("c", "a", "b")
        assertEquals(before, retained(before, promoted, interacting = true))
        assertEquals(promoted, retained(before, promoted, interacting = false))
        assertEquals(listOf("a", "c", "new"), retained(before, listOf("new", "c", "a"), interacting = true))
        assertEquals(promoted, retained(emptyList(), promoted, interacting = true))
    }
}
