package dev.chungjungsoo.gptmobile.presentation.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppNavigationViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardInsetIsConsumedOnceAndClosingRestoresViewport() {
        var keyboardHeight by mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val ime = WindowInsets(bottom = keyboardHeight)
                Box(Modifier.requiredSize(320.dp, 400.dp)) {
                    AppNavigationViewport(ime) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(ime)) {
                            Box(Modifier.fillMaxSize().testTag("content"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("content").assertHeightIsEqualTo(400.dp)
        compose.runOnIdle { keyboardHeight = 120 }
        compose.onNodeWithTag("content").assertHeightIsEqualTo(280.dp)
        compose.runOnIdle { keyboardHeight = 0 }
        compose.onNodeWithTag("content").assertHeightIsEqualTo(400.dp)
    }
}
