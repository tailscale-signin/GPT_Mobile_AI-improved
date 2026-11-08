package dev.chungjungsoo.gptmobile.presentation.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayProfileOrderTest {
    @Test fun promotionPreservesTheChosenSortWithinBothGroups() {
        val base = listOf("alpha", "bravo", "charlie", "delta")
        assertEquals(listOf("bravo", "delta", "alpha", "charlie"), promoteAvailableProfileUids(base, setOf("delta", "bravo", "filtered-out")))
        assertEquals(base, promoteAvailableProfileUids(base, emptySet()))
    }

    @Test fun freshAvailabilityPrecedesFavoritesAndSelectionIdentityNeverUsesRowPosition() {
        val base = listOf("favorite-a", "favorite-b", "other-a", "other-b")
        val selection = setOf("favorite-a", "other-b")
        val ordered = promoteAvailableProfileUids(base, setOf("favorite-b", "other-a"), setOf("favorite-a", "favorite-b"))
        assertEquals(listOf("favorite-b", "other-a", "favorite-a", "other-b"), ordered)
        assertEquals(selection, ordered.filter { it in selection }.toSet())
    }

    @Test fun touchScrollAndAccessibilityHoldExistingTargetsUntilInteractionEnds() {
        val before = listOf("a", "b", "c")
        val promoted = listOf("c", "a", "b")
        assertEquals(before, retainProfileOrder(before, promoted, interacting = true))
        assertEquals(promoted, retainProfileOrder(before, promoted, interacting = false))
        assertEquals(listOf("a", "c", "new"), retainProfileOrder(before, listOf("new", "c", "a"), interacting = true))
        assertEquals(promoted, retainProfileOrder(emptyList(), promoted, interacting = true))
    }
}
