package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Persists a positive platform-specific limit; Int.MAX_VALUE represents unlimited. */
fun PlatformSettingViewModel.updateMaxToolCalls(platform: PlatformV2, maxToolCalls: Int) {
    require(maxToolCalls > 0) { "Maximum tool calls must be greater than zero" }
    updatePlatform(platform.copy(maxToolCalls = maxToolCalls))
}
