package dev.chungjungsoo.gptmobile.presentation.renovation

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.LocalReducedMotion
import dev.chungjungsoo.gptmobile.presentation.common.Route
import dev.chungjungsoo.gptmobile.presentation.navigation.AppNavigationShell
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.theme.RenovationPalettes
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsRootContent
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsSearchRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Production composables, synthetic data only. Screenshots are evidence for visual inspection. */
class RenovationVisualInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsAllColourFamiliesLightAndDark() {
        val family = mutableStateOf("Forest")
        val mode = mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                GPTMobileTheme(themeFamily = family.value, themeMode = mode.value, updateSystemBars = false) {
                    AppNavigationShell(rememberNavController(), Route.SETTINGS) {
                        SettingsRootContent("", {}, SettingsSearchRegistry.entries, { _, _ -> }, {}, {}, {}, {})
                    }
                }
            }
        }
        RenovationPalettes.names.forEach { colour ->
            listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { themeMode ->
                compose.runOnIdle {
                    family.value = colour
                    mode.value = themeMode
                }
                compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
                capture("settings-${colour.lowercase()}-${themeMode.name.lowercase()}")
            }
        }
    }

    @Test
    fun settingsLargeTextRtlKeepsEveryDestinationReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl, LocalReducedMotion provides true) {
                GPTMobileTheme(themeFamily = "Forest", themeMode = ThemeMode.LIGHT, updateSystemBars = false) {
                    AppNavigationShell(rememberNavController(), Route.SETTINGS) {
                        SettingsRootContent("", {}, SettingsSearchRegistry.entries, { _, _ -> }, {}, {}, {}, {})
                    }
                }
            }
        }
        listOf("Appearance", "Conversations", "Memory", "Privacy & data", "Activity & diagnostics", "About").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        capture("settings-large-text-rtl-bottom")
        compose.onNodeWithText("Appearance").performScrollTo()
        capture("settings-large-text-rtl-top")
    }

    @Test
    fun expandedNavigationUsesRailWithoutHidingSettings() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density / 2.5f, 1f), LocalReducedMotion provides true) {
                GPTMobileTheme(themeFamily = "Ocean", themeMode = ThemeMode.DARK, updateSystemBars = false) {
                    AppNavigationShell(rememberNavController(), Route.SETTINGS) {
                        SettingsRootContent("", {}, SettingsSearchRegistry.entries, { _, _ -> }, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("About").performScrollTo().assertIsDisplayed()
        capture("settings-expanded-rail")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "ui-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
