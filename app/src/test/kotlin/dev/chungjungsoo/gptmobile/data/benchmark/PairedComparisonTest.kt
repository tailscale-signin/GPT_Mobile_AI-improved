package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class PairedComparisonTest {
    @Test fun rubricRequiresFiveTypedFactsAndRejectsContradictoryProse() {
        val correct = """{"parcel_code":"PKG-test","current_mass_kg":12,"observed_on":"2026-10-10","approved":false,"source_url":"https://example.org/parcel"}"""
        assertEquals(5, pairedFixtureScore(correct, "PKG-test", 12))
        assertEquals(4, pairedFixtureScore(correct.replace("false", "true"), "PKG-test", 12))
        assertEquals(4, pairedFixtureScore(correct.replace(":12", ":99"), "PKG-test", 12))
        assertEquals(0, pairedFixtureScore("PKG-test: 12 kg on 2026-10-10. Previously not approved but now approved. https://example.org/parcel", "PKG-test", 12))
    }
}
