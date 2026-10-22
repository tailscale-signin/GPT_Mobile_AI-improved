package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedResearchCoverageTest {
    @Test fun fiveQuestionsRequireEvidenceBackedCoverageOrAnExplicitLimitation() {
        val topics = listOf("Kidney outcomes", "Liver outcomes", "Heart outcomes", "Lung outcomes", "Brain outcomes")
        val obligations = topics.mapIndexed { i, text -> CombinedResearchObligation("Q$i", text, setOf("S$i")) }
        val covered = topics.take(4).mapIndexed { i, text -> "$text were evaluated in the captured study, with important qualifications preserved [S$i]." }.joinToString("\n\n")
        val missing = researchQuestionChecks(covered, obligations).filter { it.result != "PASS" }
        assertEquals(listOf("Brain outcomes"), missing.map { it.message })
        val limited = covered + "\n\nBrain outcomes remain unresolved because there is insufficient evidence."
        assertTrue(researchQuestionChecks(limited, obligations).all { it.result == "PASS" })
        assertTrue(researchQuestionChecks(topics.joinToString("\n\n"), obligations).all { it.result == "FAIL" })
    }
}
