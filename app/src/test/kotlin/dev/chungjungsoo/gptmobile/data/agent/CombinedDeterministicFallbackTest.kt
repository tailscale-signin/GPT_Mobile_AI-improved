package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDeterministicFallbackTest {
    @Test fun writerFailureShowsRecoveryStateWithoutDumpingContributorResponsesIntoTheAnswer() {
        val sources = listOf(CombinedModelResponse("a", "A", content = "One unique insight."), CombinedModelResponse("b", "B", content = "A dissenting insight."))
        val answer = deterministicCombinedFallback(sources, 10000, null)
        assertNotNull(answer)
        assertTrue(answer.orEmpty().contains("unified answer could not be completed"))
        assertTrue(answer.orEmpty().contains("contributor tabs"))
        sources.forEach { assertTrue(!answer.orEmpty().contains(it.content)) }
        assertNull(deterministicCombinedFallback(sources, 10, null))
        assertNull(deterministicCombinedFallback(sources, 10000, 3))
        assertNull(deterministicCombinedFallback(emptyList(), 10000, null))
    }
}
