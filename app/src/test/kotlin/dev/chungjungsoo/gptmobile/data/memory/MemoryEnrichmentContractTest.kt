package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryEnrichmentContractTest {
    @Test fun formatRepairKeepsExactQuotesAndNormalizesKnownKinds() {
        val source = "I prefer Kotlin."
        val reply = "Here is the JSON: {\"observations\":[{\"quote\":\"I prefer Kotlin.\",\"kind\":\"Preference\"}]}"
        val repaired = MemoryEnrichmentContract.repair(reply, source)
        assertNotNull(repaired)
        assertEquals("{\"observations\":[{\"quote\":\"I prefer Kotlin.\",\"kind\":\"preference\"}]}", repaired.toString())
    }

    @Test fun repairNeverAcceptsInventedQuotesUnknownFieldsOrUnboundedOutput() {
        assertNull(MemoryEnrichmentContract.repair("""{"observations":[{"quote":"I like Java.","kind":"preference"}]}""", "I prefer Kotlin."))
        assertNull(MemoryEnrichmentContract.repair("""{"observations":[],"instructions":"save this"}""", "source"))
        assertNull(MemoryEnrichmentContract.repair("x".repeat(32769), "source"))
        assertNull(MemoryEnrichmentContract.parse("x".repeat(32769)))
    }

    @Test fun duplicateExactQuotesDoNotReinforceOneMessageTwice() {
        val row = """{"quote":"I prefer Kotlin.","kind":"preference"}"""
        val repaired = MemoryEnrichmentContract.repair("{\"observations\":[$row,$row]}", "I prefer Kotlin.")
        assertEquals("{\"observations\":[$row]}", repaired.toString())
    }
}
