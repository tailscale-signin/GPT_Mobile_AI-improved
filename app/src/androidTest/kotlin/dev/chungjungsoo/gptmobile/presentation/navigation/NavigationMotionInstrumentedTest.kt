package dev.chungjungsoo.gptmobile.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
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

    @Test fun backUsesDirectionalMotionAndCompletesOnThePreviousScreen() {
        lateinit var navigation: NavHostController
        compose.setContent {
            MaterialTheme {
                navigation = rememberNavController()
                NavHost(
                    navigation,
                    startDestination = "home",
                    enterTransition = { forwardSlideFadeEnter() },
                    exitTransition = { forwardSlideFadeExit() },
                    popEnterTransition = { backSlideFadeEnter() },
                    popExitTransition = { backSlideFadeExit() }
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
        val initial = compose.onNodeWithTag("motion_settings").fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("motion_back").performClick()
        compose.mainClock.advanceTimeBy(96)
        val moving = compose.onNodeWithTag("motion_settings").fetchSemanticsNode().boundsInRoot.left
        assertTrue("The exiting Settings screen must slide toward the right", moving > initial)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("motion_home").assertIsDisplayed()
        compose.onNodeWithTag("motion_settings").assertDoesNotExist()
        assertEquals("home", navigation.currentDestination?.route)
    }
}
