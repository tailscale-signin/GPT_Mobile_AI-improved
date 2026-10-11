package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Owners supply a concrete control label after selecting its tab; this never changes a value. */
internal val LocalSettingSearchFocus = staticCompositionLocalOf { "" }

@Composable
internal fun Modifier.settingSearchFocus(label: String): Modifier {
    val focus = LocalSettingSearchFocus.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focus, label) {
        if (focus.isNotBlank() && focus == label) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    val result = testTag("setting_control_$label").bringIntoViewRequester(requester)
    return if (focus == label) result.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else result
}
