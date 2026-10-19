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
        val normalized = settings.migrate(emptyList())
        assertEquals(listOf("usage", "profiles", "privacy", "backup", "tools"), normalized.selectedItems)
        assertEquals(listOf("a"), normalized.selectedProfileUids)
        assertEquals("connection", normalized.walletConnectionUid)
        assertEquals(normalized, normalized.normalized())
        assertEquals(normalized, Json.decodeFromString<SettingsHomeConfiguration>(Json.encodeToString(SettingsHomeConfiguration.serializer(), normalized)))
    }

    @Test fun sixthSelectionCannotDisplaceAnExistingChoice() {
        var settings = SettingsHomeConfiguration(emptyList()).migrate(emptyList())
        SettingsHomeConfiguration.CATEGORIES.take(5).forEach { settings = settings.withItem(it, true) }
        assertEquals(settings, settings.withItem("backup", true))
        settings = settings.withItem("usage", false).withItem("backup", true)
        assertEquals(5, settings.selectedItems.size)
        assertFalse("usage" in settings.selectedItems)
        assertTrue("backup" in settings.selectedItems)
        settings.selectedItems.toList().forEach { settings = settings.withItem(it, false) }
        assertTrue(settings.selectedItems.isEmpty())
    }

    @Test fun migrationReadsAllLegacyProfilesBeforeCappingCategoriesAndSeedsOnlyOnce() {
        val legacy = SettingsHomeConfiguration(listOf("usage", "profile:old", "privacy", "tools", "backup", "profiles", "profile:last", "openrouter_wallet"))
        val decoded = Json.decodeFromString<SettingsHomeConfiguration>(Json.encodeToString(SettingsHomeConfiguration.serializer(), legacy))
        val migrated = decoded.migrate(listOf("enabled", "old"))
        assertEquals(listOf("usage", "profiles", "privacy", "tools", "backup"), migrated.selectedItems)
        assertEquals(listOf("old", "last", "enabled"), migrated.selectedProfileUids)
        assertEquals(migrated, migrated.migrate(listOf("new-profile")))
        assertEquals(migrated, migrated.withItem("profile:injected", true))
    }

    @Test fun malformedOptionalChecklistsDoNotBreakSettingsOrDropUnknownFields() {
        val decoded = FeatureSettingsJson.decode("""{"settingsHome":{"schemaVersion":null,"selectedProfileUids":["a",null,4],"selectedToolSubjects":[{"ownerUid":"valid"},{"kind":"FUTURE"}],"futureDisplay":true}}""")
        assertEquals(listOf("a"), decoded.settingsHome.selectedProfileUids)
        assertEquals(listOf(HomeToolSubject("valid")), decoded.settingsHome.selectedToolSubjects)
        assertEquals(1, decoded.settingsHome.schemaVersion)
    }

    @Test fun nestedSubjectsDoNotConsumeCategorySlotsOrDisappearWhenCategoryIsDisabled() {
        val settings = SettingsHomeConfiguration(selectedProfileUids = (1..40).map { "p$it" }).migrate(emptyList())
        val disabled = settings.withItem(SettingsHomeConfiguration.PROFILES, false)
        assertEquals(40, disabled.selectedProfileUids.size)
        assertEquals(2, disabled.selectedItems.size)
        assertEquals(settings, disabled.withItem(SettingsHomeConfiguration.PROFILES, true).copy(selectedItems = settings.selectedItems))
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
        val decoded = FeatureSettingsJson.decode(raw)
        val before = decoded.copy(settingsHome = decoded.settingsHome.migrate(emptyList()))
        assertEquals(5, before.settingsHome.selectedItems.size)
        val migratedRaw = FeatureSettingsJson.merge(raw, decoded, before)
        val after = before.copy(settingsHome = before.settingsHome.withItem("usage", false))
        val encoded = Json.parseToJsonElement(FeatureSettingsJson.merge(migratedRaw, before, after)).jsonObject
        assertEquals("9", encoded["futureRoot"].toString())
        assertEquals("{\"x\":1}", encoded.getValue("settingsHome").jsonObject["futureDisplay"].toString())
        assertEquals(4, FeatureSettingsJson.decode(encoded.toString()).settingsHome.selectedItems.size)
    }

    private fun activity(status: String) = SettingsHomeActivity("run", "profile", "OPENROUTER", "model", status, 1_000, null, null, null, null, null)
}
