package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId

/** Apply the requested default-on rollout once; later explicit off settings remain authoritative. */
internal object GitHubCopilotDefaults {
    const val VERSION = 1

    fun migrate(settings: AppFeatureSettings): AppFeatureSettings {
        if (settings.githubCopilotDefaultsVersion >= VERSION) return settings
        val previous = settings.pluginExecution[ToolPluginId.GITHUB] ?: PluginExecutionSettings()
        return settings.copy(
            githubCopilotDefaultsVersion = VERSION,
            pluginExecution = settings.pluginExecution + (ToolPluginId.GITHUB to previous.copy(githubCopilotEnabled = true))
        )
    }
}
