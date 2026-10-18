package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.DebugMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchRegistryTest {
    @Test
    fun controlKeysAreUniqueAndEveryFeatureHasAnExactTarget() {
        val entries = SettingsSearchRegistry.entries
        assertEquals(entries.size, entries.map { it.key }.distinct().size)
        AppFeature.entries.filterNot { it in setOf(AppFeature.OPENROUTER_BATCH, AppFeature.LOCAL_ASSIST) }.forEach { feature ->
            val target = entries.single { it.key == "feature.${feature.name}" }
            assertEquals(SettingsSearchDestination.ADVANCED, target.destination)
            assertEquals(feature.name, target.focus)
        }
        DebugMetric.entries.forEach { metric ->
            assertEquals(metric.name, entries.single { it.key == "debug.${metric.name}" }.focus)
        }
    }

    @Test
    fun oldTerminologyFindsTheOwnerWithoutChangingPermissions() {
        assertTrue(SettingsSearchRegistry.search("tool connections").any { it.destination == SettingsSearchDestination.TOOLS })
        assertTrue(SettingsSearchRegistry.search("platform").any { it.destination == SettingsSearchDestination.PROFILES })
        assertTrue(SettingsSearchRegistry.search("recovery password").any { it.destination == SettingsSearchDestination.BACKUP })
        assertTrue(SettingsSearchRegistry.search("unknown setting xyz").isEmpty())
        assertTrue(SettingsSearchRegistry.search(" ").isEmpty())
    }
}
