package dev.chungjungsoo.gptmobile.presentation.common

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamingGlassPolicyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun streamingSuppressesEvenForcedBlurAndRestoresItWhenIdle() {
        val streaming = mutableStateOf(false)
        compose.setContent {
            GlassBackdropHost {
                GlassBackdropBox(blurEnabled = !streaming.value) {
                    Text(if (hasGlassBackdrop(force = true)) "blur available" else "solid tint")
                }
            }
        }
        compose.onNodeWithText("blur available").assertExists()
        compose.runOnIdle { streaming.value = true }
        compose.onNodeWithText("solid tint").assertExists()
        compose.runOnIdle { streaming.value = false }
        compose.onNodeWithText("blur available").assertExists()
    }
}
