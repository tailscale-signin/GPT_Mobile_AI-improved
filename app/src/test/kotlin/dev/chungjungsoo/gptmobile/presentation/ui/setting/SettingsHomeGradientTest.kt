package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.AppSceneBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w600dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeGradientTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rootGradientStartsAtSeventyPercentAndDoesNotDarkenForeground() = verifyScene(200, 800, false)

    @Test fun resizedFullScreenSceneDoesNotStackTheUnderlyingGradient() = verifyScene(500, 300, true)

    private fun verifyScene(width: Int, height: Int, nested: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = lightColorScheme(background = Color.White, scrim = Color.Black)) {
                    Box(Modifier.size(width.dp, height.dp).testTag("scene")) {
                        AppSceneBackground {
                            if (nested) AppSceneBackground { Foreground() } else Foreground()
                        }
                    }
                }
            }
        }
        val pixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        assertEquals(1f, pixels[1, (height * 0.69f).toInt()].red, 0.01f)
        assertEquals(0.86f, pixels[1, (height * 0.85f).toInt()].red, 0.02f)
        assertEquals(0.72f, pixels[1, height - 2].red, 0.02f)
        assertTrue(pixels[width / 2, height - 5].red > 0.99f)
    }

    @androidx.compose.runtime.Composable
    private fun Foreground() {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomCenter).size(20.dp).background(Color.White))
        }
    }
}
