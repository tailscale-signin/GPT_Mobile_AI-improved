package dev.chungjungsoo.gptmobile.data.research.feature

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchConsentReceiptTest {
    @Test fun legacySettingsHaveNoInventedConsentAndDecisionsSurviveSerialization() {
        val legacy = Json.decodeFromString<ResearchFeatureSettings>("{}")
        assertTrue(legacy.consentReceipts.isEmpty())
        val recorded = legacy.recordConsent("prompt", "profile", "OFFERED", 1).recordConsent("prompt", "profile", "ACCEPTED", 2)
        val restored = Json.decodeFromString<ResearchFeatureSettings>(Json.encodeToString(recorded))
        assertEquals(listOf("OFFERED", "ACCEPTED"), restored.consentReceipts.map { it.decision })
        assertEquals(listOf("profile", "profile"), restored.consentReceipts.map { it.profileUid })
    }

    @Test fun aBoundedDismissalHistoryNeverClaimsAcceptance() {
        var settings = ResearchFeatureSettings()
        repeat(50) { settings = settings.recordConsent("p$it", "profile", "DISMISSED", it.toLong()) }
        assertEquals(32, settings.consentReceipts.size)
        assertEquals("p18", settings.consentReceipts.first().promptId)
        assertTrue(settings.consentReceipts.none { it.decision == "ACCEPTED" })
    }
}
