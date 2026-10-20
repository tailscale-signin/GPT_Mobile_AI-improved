package dev.chungjungsoo.gptmobile.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.chungjungsoo.gptmobile.presentation.common.backSlideFadeEnter
import dev.chungjungsoo.gptmobile.presentation.common.backSlideFadeExit
import dev.chungjungsoo.gptmobile.presentation.common.forwardSlideFadeEnter
import dev.chungjungsoo.gptmobile.presentation.common.forwardSlideFadeExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backUsesFadeWithoutScalingAndCompletesOnThePreviousScreen() {
        lateinit var navigation: NavHostController
        compose.setContent {
            MaterialTheme {
                navigation = rememberNavController()
                NavHost(
                    navigation,
                    modifier = Modifier.fillMaxSize().background(Color.Blue).testTag("motion_frame"),
                    startDestination = "home",
                    enterTransition = { forwardSlideFadeEnter() },
                    exitTransition = { forwardSlideFadeExit() },
                    popEnterTransition = { backSlideFadeEnter() },
                    popExitTransition = { backSlideFadeExit() },
                    predictivePopEnterTransition = { _ -> backSlideFadeEnter() },
                    predictivePopExitTransition = { _ -> backSlideFadeExit() }
                ) {
                    composable("home") { Box(Modifier.fillMaxSize().background(Color.Blue).testTag("motion_home")) }
                    composable("settings") {
                        Box(Modifier.fillMaxSize().background(Color.Green).testTag("motion_settings")) {
                            Button(onClick = { navigation.popBackStack() }, modifier = Modifier.testTag("motion_back")) { Text("Back") }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { navigation.navigate("settings") }
        compose.waitForIdle()
        val initial = compose.onNodeWithTag("motion_settings").fetchSemanticsNode().boundsInRoot
        val initialPixels = compose.onNodeWithTag("motion_frame").captureToImage().toPixelMap()
        val sampleX = initialPixels.width * 3 / 4
        val sampleY = initialPixels.height * 3 / 4
        val before = initialPixels[sampleX, sampleY]
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("motion_back").performClick()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val during = compose.onNodeWithTag("motion_settings").fetchSemanticsNode().boundsInRoot
        assertEquals("Fade must not translate the left edge", initial.left, during.left, 0.5f)
        assertEquals("Fade must not translate the top edge", initial.top, during.top, 0.5f)
        assertEquals("Fade must not shrink the right edge", initial.right, during.right, 0.5f)
        assertEquals("Fade must not shrink the bottom edge", initial.bottom, during.bottom, 0.5f)
        val pixels = compose.onNodeWithTag("motion_frame").captureToImage().toPixelMap()
        val fading = pixels[sampleX, sampleY]
        assertTrue("Settings must visibly fade before it leaves", fading.green > 0.01f && fading.green < before.green - 0.01f)
        assertTrue("The previous screen must show through the fade", fading.blue > before.blue + 0.01f && fading.blue < 0.99f)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("motion_home").assertIsDisplayed()
        compose.onNodeWithTag("motion_settings").assertDoesNotExist()
        assertEquals("home", navigation.currentDestination?.route)
    }
}
