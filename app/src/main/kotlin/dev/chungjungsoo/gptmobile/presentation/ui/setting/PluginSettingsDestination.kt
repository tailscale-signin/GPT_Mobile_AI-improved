package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceDefinition

internal enum class PluginSettingsDestination { DEEP_RESEARCH, DELEGATION, MEMORY, HEALTH, GENERIC, UNAVAILABLE }

internal fun pluginSettingsDestination(definition: ToolServiceDefinition?): PluginSettingsDestination = when (definition?.id) {
    null -> PluginSettingsDestination.UNAVAILABLE
    ToolPluginId.DEEP_RESEARCH -> PluginSettingsDestination.DEEP_RESEARCH
    ToolPluginId.MODEL_DELEGATION -> PluginSettingsDestination.DELEGATION
    ToolPluginId.LOCAL_MEMORY -> PluginSettingsDestination.MEMORY
    ToolPluginId.HEALTH_CONNECT -> PluginSettingsDestination.HEALTH
    else -> if (definition?.supportsGenericSettings == true) PluginSettingsDestination.GENERIC else PluginSettingsDestination.UNAVAILABLE
}
