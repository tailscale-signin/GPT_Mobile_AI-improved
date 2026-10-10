package dev.chungjungsoo.gptmobile.presentation.icons

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
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

@Preview(name = "Circular badge in wide bounds, light purple theme", widthDp = 220, heightDp = 96)
@Composable
fun GptMobileWidePreview() {
    GPTMobileTheme(themeMode = ThemeMode.LIGHT, customPrimaryArgb = 0xFF7851A9) {
        GptMobileStartScreen(Modifier.fillMaxSize().padding(8.dp))
    }
}

@Preview(name = "Circular badge in tall bounds, dark orange theme", widthDp = 96, heightDp = 180)
@Composable
fun GptMobileTallPreview() {
    GPTMobileTheme(themeMode = ThemeMode.DARK, customPrimaryArgb = 0xFFFFA65C) {
        GptMobileStartScreen(Modifier.fillMaxSize().padding(8.dp))
    }
}
