package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel evidence distinguishes backdrop filtering from an unchanged translucent tint. */
@SdkSuppress(minSdkVersion = 32)
class GlassBackdropInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dialogBlurSwitchActuallyFiltersTheBackground() {
        var blur by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOverlayMaterial provides "glass", LocalOverlayBlur provides if (blur) "auto" else "off") {
                    GlassBackdropHost {
                        Canvas(Modifier.fillMaxSize()) {
                            for (x in 0..size.width.toInt() step 8) {
                                drawRect(if ((x / 8) % 2 == 0) Color.Red else Color.Blue, Offset(x.toFloat(), 0f), Size(8f, size.height))
                            }
                        }
                        FadingAlertDialog({}, { Text("Close") }, modifier = Modifier.testTag("glass_dialog"), text = { Box(Modifier.height(120.dp)) })
                    }
                }
            }
        }
        fun variation(name: String): Double {
            compose.waitForIdle()
            // Capture the compositor: the off-state window is transparent and PixelCopy of
            // that window alone omits the activity behind it.
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(instrumentation.targetContext.filesDir, "glass-$name.png").outputStream().use {
                check(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            val y = pixels.height / 2
            val values = (pixels.width * 7 / 20 until pixels.width * 13 / 20).map { x ->
                val color = pixels.getPixel(x, y)
                (android.graphics.Color.red(color) - android.graphics.Color.blue(color)) / 255.0
            }
            pixels.recycle()
            val mean = values.average()
            return values.map { (it - mean) * (it - mean) }.average()
        }
        val sharp = variation("off")
        compose.runOnIdle { blur = true }
        val filtered = variation("40dp")
        assertTrue("Fixture must contain visible alternating background colors", sharp > 0.002)
        assertTrue("Blur must reduce backdrop detail: sharp=$sharp blurred=$filtered", filtered < sharp * 0.6)
    }
}
