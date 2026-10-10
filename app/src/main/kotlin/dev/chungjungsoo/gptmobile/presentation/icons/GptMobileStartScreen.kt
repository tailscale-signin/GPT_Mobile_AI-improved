package dev.chungjungsoo.gptmobile.presentation.icons

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.ThemedAppIcon
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme

/** Compatibility wrapper for the shared, theme-aware brand mark. */
@Composable
fun GptMobileStartScreen(modifier: Modifier = Modifier) {
    ThemedAppIcon(modifier)
}

@Preview
@Composable
fun GptMobileStartScreenPreview() {
    GPTMobileTheme {
        GptMobileStartScreen(Modifier.size(240.dp))
    }
}
