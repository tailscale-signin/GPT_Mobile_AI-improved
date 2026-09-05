package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chungjungsoo.gptmobile.data.catalog.McpPreset
import dev.chungjungsoo.gptmobile.presentation.ui.mcp.McpMarketplaceScreen

/**
 * Adapter for backward-compatibility delegating to the modernized, full-screen McpMarketplaceScreen.
 */
@Composable
fun McpMarketplaceDialog(
    installedAliases: Set<String>,
    onInstallPreset: (McpPreset) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    McpMarketplaceScreen(
        installedAliases = installedAliases,
        onNavigationClick = onDismissRequest,
        onInstallPresetWithConfig = { preset, _, _, _, _, _, _ ->
            onInstallPreset(preset)
            onDismissRequest()
        },
        modifier = modifier
    )
}
