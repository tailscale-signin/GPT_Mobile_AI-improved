package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedOwnerResolverTest {
    @Test fun failedPreferredProfileFallsBackToASelectedCompletedContributor() {
        val sources = listOf(source("preferred", "FAILED"), source("peer"))
        assertEquals(listOf("peer"), CombinedOwnerResolver.eligibleOrder(sources, listOf("preferred", "peer"), "preferred", setOf("preferred", "peer")))
    }

    @Test fun fallbackOrderIsStableAndNeverAdmitsAnUnselectedProfile() {
        val sources = listOf(source("unselected"), source("z"), source("a"))
        assertEquals(listOf("a", "z"), CombinedOwnerResolver.eligibleOrder(sources, listOf("z", "a"), "unselected", setOf("a", "z", "unselected")))
        assertEquals(listOf("a", "z"), CombinedOwnerResolver.eligibleOrder(sources.reversed(), listOf("a", "z"), null, setOf("a", "z")))
    }

    @Test fun oneHealthyContributorCanFinishButDisabledProfilesCannot() {
        val sources = listOf(source("only"))
        assertEquals(listOf("only"), CombinedOwnerResolver.eligibleOrder(sources, listOf("only"), "only", setOf("only")))
        assertEquals(emptyList<String>(), CombinedOwnerResolver.eligibleOrder(sources, listOf("only"), "only", emptySet()))
    }

    private fun source(uid: String, status: String = "COMPLETED") = CombinedModelResponse(uid, uid, content = "A usable original contribution.", completionStatus = status)
}
