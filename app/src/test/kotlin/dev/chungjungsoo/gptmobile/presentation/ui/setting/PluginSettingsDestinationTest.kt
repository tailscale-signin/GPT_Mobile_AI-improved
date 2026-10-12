package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.model.ToolServiceDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PluginSettingsDestinationTest {
    @Test fun canonicalCatalogueHasAnExplicitRouteForEveryIntegratedService() {
        ToolServiceCatalog.integrated.forEach { assertNotEquals(it.id, PluginSettingsDestination.UNAVAILABLE, pluginSettingsDestination(it)) }
        assertEquals(PluginSettingsDestination.DEEP_RESEARCH, pluginSettingsDestination(ToolServiceCatalog.integrated.single { it.id == ToolPluginId.DEEP_RESEARCH }))
    }

    @Test fun unknownOrRemovedPluginsHaveARecoverableDestination() {
        assertEquals(PluginSettingsDestination.UNAVAILABLE, pluginSettingsDestination(null))
        assertEquals(PluginSettingsDestination.UNAVAILABLE, pluginSettingsDestination(ToolServiceDefinition("future", "Future", "", "search")))
        assertEquals(PluginSettingsDestination.GENERIC, pluginSettingsDestination(ToolServiceDefinition("future", "Future", "", "search", supportsGenericSettings = true)))
    }
}
