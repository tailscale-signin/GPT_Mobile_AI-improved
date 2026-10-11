package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Review images are evidence for human approval, never automatically accepted as goldens. */
class ComposerVisualReviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun light() = render("light")

    @Test fun dark() = render("dark", dark = true)

    @Test fun custom() = render("custom", dark = true, custom = true)

    @Test fun largeFontRtl() = render("large-font-rtl", dark = true, rtl = true, fontScale = 2f)

    private fun render(name: String, dark: Boolean = false, custom: Boolean = false, rtl: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            val colors = if (custom) {
                darkColorScheme(primary = Color(0xFFB4F0C0), onPrimary = Color(0xFF003916), surface = Color(0xFF111713), onSurface = Color(0xFFDEE5DF), onSurfaceVariant = Color(0xFFBEC9BF))
            } else if (dark) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = colors) {
                    ChatInputBox(inputState = rememberTextFieldState("A follow-up message remains editable during generation"), isRunning = true, onCancelButtonClick = {}, onSendButtonClick = {})
                }
            }
        }
        compose.onNodeWithTag("chat_stop").assertIsDisplayed()
        compose.onNodeWithTag("chat_send").assertIsDisplayed()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
