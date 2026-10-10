package dev.chungjungsoo.gptmobile.data.marketplace

import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import java.time.LocalDate
import java.time.ZoneOffset

/** Marketplace scope only: profile permissions and provider health are checked separately at execution. */
fun nativeMarketplaceAvailability(
    entry: GitHubMarketplacePackage,
    installation: NativePluginInstallation?,
    features: AppFeatureSettings,
    now: Long = System.currentTimeMillis()
): String = when {
    installation == null -> "Available"
    !installation.ready(entry) -> "Needs setup"
    !installation.enabled -> "Disabled"
    !features.isToolPluginEnabled(entry.id) -> "Package access off"
    !features.isToolPluginEnabled(ToolServiceCatalog.forPackage(entry).id) -> "Service access off"
    installation.nextRequestAt > now -> "Cooldown · ${((installation.nextRequestAt - now + 999) / 1000)}s"
    installation.usageDay == LocalDate.now(ZoneOffset.UTC).toString() && installation.usageCount >= installation.dailyLimit -> "Daily limit reached"
    else -> "Enabled · profile access applies"
}
