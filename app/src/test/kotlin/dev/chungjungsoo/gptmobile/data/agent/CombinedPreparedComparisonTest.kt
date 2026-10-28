package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedPreparedComparisonTest {
    @Test fun cachedComparisonsPreserveDirectionalClauseAndQualifierChecks() {
        val samples = listOf(
            "France joined the organization in 1986.",
            "France joined the organization in 1996.",
            "France may have joined the organization in 1986.",
            "France did not join the organization in 1986.",
            "Napoleon seized power in 1799 and was crowned emperor in 1804.",
            "Napoleon seized power in 1804 and was crowned emperor in 1799.",
            "The silver mines of Athens financed construction of naval ships.",
            "Athenian silver mines funded the construction of naval ships."
        )
        val prepared = samples.associateWith(CombinedClaimMatcher::prepare)
        samples.forEach { source ->
            samples.forEach { answer ->
                assertEquals("$source / $answer", CombinedClaimMatcher.match(source, answer), CombinedClaimMatcher.match(prepared.getValue(source), prepared.getValue(answer)))
            }
        }
    }
}
