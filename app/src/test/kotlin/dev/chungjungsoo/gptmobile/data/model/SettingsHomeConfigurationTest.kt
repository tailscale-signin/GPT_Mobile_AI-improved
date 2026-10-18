package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHomeConfigurationTest {
    @Test fun selectionsArePortableDistinctAndLimitedToFive() {
        val settings = SettingsHomeConfiguration(listOf(" usage ", "usage", "", "profile:a", "privacy", "future_item", "backup", "tools"), " connection ")
        val normalized = settings.normalized()
        assertEquals(listOf("usage", "profile:a", "privacy", "future_item", "backup"), normalized.selectedItems)
        assertEquals("connection", normalized.walletConnectionUid)
        assertEquals(normalized, normalized.normalized())
        assertEquals(normalized, Json.decodeFromString<SettingsHomeConfiguration>(Json.encodeToString(SettingsHomeConfiguration.serializer(), normalized)))
    }

    @Test fun sixthSelectionCannotDisplaceAnExistingChoice() {
        var settings = SettingsHomeConfiguration(emptyList())
        repeat(5) { settings = settings.withItem("item$it", true) }
        assertEquals(settings, settings.withItem("sixth", true))
        settings = settings.withItem("item2", false).withItem("sixth", true)
        assertEquals(5, settings.selectedItems.size)
        assertFalse("item2" in settings.selectedItems)
        assertTrue("sixth" in settings.selectedItems)
        settings.selectedItems.toList().forEach { settings = settings.withItem(it, false) }
        assertTrue(settings.selectedItems.isEmpty())
    }

    @Test fun everyLayoutFromZeroToFiveHasNoMissingDuplicatedOrEmptyTiles() {
        for (count in 0..5) {
            for (columns in 1..2) {
                val rows = settingsHomeRows(count, columns)
                assertEquals((0 until count).toList(), rows.flatMap { it.toList() })
                assertTrue(rows.all { !it.isEmpty() && it.count() <= columns })
            }
        }
        assertEquals(listOf(0..0, 1..2, 3..4), settingsHomeRows(5, 2))
        assertEquals(listOf(0..1, 2..3), settingsHomeRows(4, 2))
    }

    @Test fun enabledIsNeverTreatedAsVerifiedAvailability() {
        assertEquals(SettingsHomeTone.UNKNOWN, settingsHomeProfileStatus(true, true, null, 1_000).tone)
        assertEquals("Not checked", settingsHomeProfileStatus(true, true, null, 1_000).label)
        assertEquals(SettingsHomeTone.NEGATIVE, settingsHomeProfileStatus(false, true, activity("COMPLETED"), 1_000).tone)
        assertEquals("Disabled", settingsHomeProfileStatus(false, true, activity("COMPLETED"), 1_000).label)
        assertEquals("Needs a model", settingsHomeProfileStatus(true, false, activity("COMPLETED"), 1_000).label)
    }

    @Test fun requestOutcomesHaveDistinctHonestFreshnessStates() {
        assertEquals(SettingsHomeTone.POSITIVE, settingsHomeProfileStatus(true, true, activity("COMPLETED"), 1_000).tone)
        assertEquals("Last request succeeded", settingsHomeProfileStatus(true, true, activity("COMPLETED"), 1_000).label)
        for (status in listOf("FAILED", "INTERRUPTED")) assertEquals(SettingsHomeTone.NEGATIVE, settingsHomeProfileStatus(true, true, activity(status), 1_000).tone)
        for (status in listOf("RUNNING", "QUEUED")) assertEquals(SettingsHomeTone.ACTIVE, settingsHomeProfileStatus(true, true, activity(status), 1_000).tone)
        for (status in listOf("CANCELED", "NEW_UNKNOWN_STATUS")) assertEquals(SettingsHomeTone.UNKNOWN, settingsHomeProfileStatus(true, true, activity(status), 1_000).tone)
        for (status in listOf("COMPLETED", "FAILED", "RUNNING")) assertEquals(SettingsHomeTone.STALE, settingsHomeProfileStatus(true, true, activity(status), 1_601).tone)
        assertEquals(SettingsHomeTone.STALE, settingsHomeProfileStatus(true, true, activity("COMPLETED"), 999).tone)
    }

    @Test fun importedSelectionsNormalizeWithoutLosingFutureHomeFields() {
        val raw = """{"settingsHome":{"selectedItems":["usage","usage","privacy","tools","backup","profiles","openrouter_wallet"],"futureDisplay":{"x":1}},"futureRoot":9}"""
        val before = FeatureSettingsJson.decode(raw)
        assertEquals(5, before.settingsHome.selectedItems.size)
        val after = before.copy(settingsHome = before.settingsHome.withItem("usage", false))
        val encoded = Json.parseToJsonElement(FeatureSettingsJson.merge(raw, before, after)).jsonObject
        assertEquals("9", encoded["futureRoot"].toString())
        assertEquals("{\"x\":1}", encoded.getValue("settingsHome").jsonObject["futureDisplay"].toString())
        assertEquals(4, FeatureSettingsJson.decode(encoded.toString()).settingsHome.selectedItems.size)
    }

    private fun activity(status: String) = SettingsHomeActivity("run", "profile", "OPENROUTER", "model", status, 1_000, null, null, null, null, null)
}
