package dev.chungjungsoo.gptmobile.data.marketplace

import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import java.time.LocalDate
import java.time.ZoneOffset

enum class MarketplaceAvailabilityCode {
    AVAILABLE,
    NEEDS_SETUP,
    DISABLED,
    PACKAGE_ACCESS_DISABLED,
    SERVICE_ACCESS_DISABLED,
    COOLDOWN,
    DAILY_LIMIT_REACHED,
    READY,
    NOT_REACHABLE,
    INCOMPATIBLE
}

enum class MarketplaceAvailabilityAction { SETUP, ENABLE, OPEN_ACCESS, WAIT, RETRY, NONE }

data class MarketplaceAvailability(
    val code: MarketplaceAvailabilityCode,
    val action: MarketplaceAvailabilityAction,
    val retryAt: Long? = null
) {
    fun displayLabel(now: Long = System.currentTimeMillis()): String = when (code) {
        MarketplaceAvailabilityCode.AVAILABLE -> "Available"
        MarketplaceAvailabilityCode.NEEDS_SETUP -> "Needs setup"
        MarketplaceAvailabilityCode.DISABLED -> "Disabled"
        MarketplaceAvailabilityCode.PACKAGE_ACCESS_DISABLED -> "Package access off"
        MarketplaceAvailabilityCode.SERVICE_ACCESS_DISABLED -> "Service access off"
        MarketplaceAvailabilityCode.COOLDOWN -> "Cooldown · ${(((retryAt ?: now) - now + 999) / 1000).coerceAtLeast(0)}s"
        MarketplaceAvailabilityCode.DAILY_LIMIT_REACHED -> "Daily limit reached"
        MarketplaceAvailabilityCode.READY -> "Enabled · profile access applies"
        MarketplaceAvailabilityCode.NOT_REACHABLE -> "Not reachable"
        MarketplaceAvailabilityCode.INCOMPATIBLE -> "Incompatible"
    }
}

/** Marketplace scope only: profile permissions and provider health are checked separately at execution. */
fun resolveNativeMarketplaceAvailability(
    entry: GitHubMarketplacePackage,
    installation: NativePluginInstallation?,
    features: AppFeatureSettings,
    now: Long = System.currentTimeMillis()
): MarketplaceAvailability = when {
    installation == null -> MarketplaceAvailability(MarketplaceAvailabilityCode.AVAILABLE, MarketplaceAvailabilityAction.SETUP)
    !installation.ready(entry) -> MarketplaceAvailability(MarketplaceAvailabilityCode.NEEDS_SETUP, MarketplaceAvailabilityAction.SETUP)
    !installation.enabled -> MarketplaceAvailability(MarketplaceAvailabilityCode.DISABLED, MarketplaceAvailabilityAction.ENABLE)
    !features.isToolPluginEnabled(entry.id) -> MarketplaceAvailability(MarketplaceAvailabilityCode.PACKAGE_ACCESS_DISABLED, MarketplaceAvailabilityAction.OPEN_ACCESS)
    !features.isToolPluginEnabled(ToolServiceCatalog.forPackage(entry).id) -> MarketplaceAvailability(MarketplaceAvailabilityCode.SERVICE_ACCESS_DISABLED, MarketplaceAvailabilityAction.OPEN_ACCESS)
    installation.nextRequestAt > now -> MarketplaceAvailability(MarketplaceAvailabilityCode.COOLDOWN, MarketplaceAvailabilityAction.WAIT, installation.nextRequestAt)
    installation.usageDay == LocalDate.now(ZoneOffset.UTC).toString() && installation.usageCount >= installation.dailyLimit -> MarketplaceAvailability(MarketplaceAvailabilityCode.DAILY_LIMIT_REACHED, MarketplaceAvailabilityAction.WAIT)
    else -> MarketplaceAvailability(MarketplaceAvailabilityCode.READY, MarketplaceAvailabilityAction.NONE)
}

/** Kept for older card callers while status logic moves to typed states. */
fun nativeMarketplaceAvailability(
    entry: GitHubMarketplacePackage,
    installation: NativePluginInstallation?,
    features: AppFeatureSettings,
    now: Long = System.currentTimeMillis()
): String = resolveNativeMarketplaceAvailability(entry, installation, features, now).displayLabel(now)

fun resolveMcpMarketplaceAvailability(
    installed: Boolean,
    enabled: Boolean,
    setupComplete: Boolean,
    reachable: Boolean? = null,
    retryAt: Long? = null,
    now: Long = System.currentTimeMillis()
): MarketplaceAvailability = when {
    !installed -> MarketplaceAvailability(MarketplaceAvailabilityCode.AVAILABLE, MarketplaceAvailabilityAction.SETUP)
    !setupComplete -> MarketplaceAvailability(MarketplaceAvailabilityCode.NEEDS_SETUP, MarketplaceAvailabilityAction.SETUP)
    !enabled -> MarketplaceAvailability(MarketplaceAvailabilityCode.DISABLED, MarketplaceAvailabilityAction.ENABLE)
    retryAt != null && retryAt > now -> MarketplaceAvailability(MarketplaceAvailabilityCode.COOLDOWN, MarketplaceAvailabilityAction.WAIT, retryAt)
    reachable == false -> MarketplaceAvailability(MarketplaceAvailabilityCode.NOT_REACHABLE, MarketplaceAvailabilityAction.RETRY)
    else -> MarketplaceAvailability(MarketplaceAvailabilityCode.READY, MarketplaceAvailabilityAction.NONE)
}
