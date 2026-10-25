package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedContentIndexTest {
    @Test fun frozenBodiesReuseExtractionButChangedBodyOwnerAndAttemptInvalidateIt() {
        CombinedContentIndexes.clear()
        val source = CombinedModelResponse("bridge", "Bridge", content = "Portugal joined the EU in 1986.", attemptId = "one", createdAt = 10)
        val first = CombinedContentIndexes.get(listOf(source))
        assertSame(first, CombinedContentIndexes.get(listOf(source.copy(completionStatus = "COMPLETED"))))
        assertSame(first.records, CombinedClaimInventory.freeze(listOf(source)).claims)
        val changed = CombinedContentIndexes.get(listOf(source.copy(content = "Portugal joined Schengen in 1995.")))
        assertNotSame(first, changed)
        assertTrue(changed.records.single().proposition.contains("1995"))
        val owner = CombinedContentIndexes.get(listOf(source.copy(platformUid = "other", attemptId = "two")))
        assertEquals(setOf("other"), owner.records.single().profileUids)
        assertEquals("two", owner.records.single().occurrences.single().attemptId)
        assertEquals("one", first.records.single().occurrences.single().attemptId)
    }

    @Test fun sourceCacheReorderingAndPartialReuseMatchUncachedExtraction() {
        CombinedContentIndexes.clear()
        val a = CombinedModelResponse("a", "A", content = "France has Paris as its capital.\n\nPortugal joined the EU in 1986.", attemptId = "a-1", claimExtractionVersion = 2)
        val b = CombinedModelResponse("b", "B", content = "Portugal joined the EU in 1986.\n\nThe river flows west.", attemptId = "b-1", claimExtractionVersion = 2)
        listOf(listOf(a, b), listOf(b, a), listOf(a.copy(content = "A newly changed fact."), b)).forEach { sources ->
            assertEquals(CombinedClaimSemantics.extractRecords(sources), CombinedContentIndexes.get(sources).records)
        }
    }
}
