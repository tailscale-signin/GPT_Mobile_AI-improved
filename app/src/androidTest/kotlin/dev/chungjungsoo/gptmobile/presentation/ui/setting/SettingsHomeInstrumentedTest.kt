package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsHomeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneToFiveItemsAtCompactWidth() = renderLayouts("compact", 320, 1f)

    @Test fun oneToFiveItemsAtWideWidth() = renderLayouts("wide", 600, 1f)

    @Test fun oneToFiveItemsAtLargeFontAndRtl() = renderLayouts("large-rtl", 320, 2f, rtl = true)

    private fun renderLayouts(name: String, width: Int, fontScale: Float, rtl: Boolean = false) {
        var count by mutableStateOf(0)
        var dark by mutableStateOf(false)
        val points = listOf(
            SettingsHomePoint("wallet", "OpenRouter Wallet", "$6.99999999 USD", "Updated just now · Account", SettingsHomeTone.POSITIVE),
            SettingsHomePoint("enabled", "Selected AI profile", "Enabled", "No recorded request", SettingsHomeTone.POSITIVE, "Not checked", SettingsHomeTone.UNKNOWN),
            SettingsHomePoint("disabled", "Another AI profile", "Disabled", "Last update 1 h ago", SettingsHomeTone.NEGATIVE, "Stale result", SettingsHomeTone.STALE),
            SettingsHomePoint("tools", "Plugins & Tools", "4 configured connections", "Individual tool permissions still apply"),
            SettingsHomePoint("privacy", "Privacy choices", "Diagnostics off", "Local Assist off · location tool allowed")
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) { SettingsHomePanel(points.take(count), onClick = {}) }
                }
            }
        }
        compose.onNodeWithTag("settings_home_empty", useUnmergedTree = true).assertIsDisplayed()
        for (darkMode in listOf(false, true)) {
            for (size in 1..5) {
                compose.runOnIdle {
                    count = size
                    dark = darkMode
                }
                points.take(size).forEach { point ->
                    val node = compose.onNodeWithTag("settings_home_point_${point.id}", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    assertTrue("Tile must have usable width", bounds.width > 0)
                    assertTrue("Tile must have usable height", bounds.height > 0)
                }
                capture("$name-${if (darkMode) "dark" else "light"}-$size")
            }
        }
    }

    @Test fun homeScreenNamesItsDestinationEnforcesFiveAndAllowsZero() {
        var config by mutableStateOf(SettingsHomeConfiguration())
        var back = 0
        compose.setContent {
            MaterialTheme {
                SettingsHomeScreen(
                    configuration = config,
                    points = emptyList(),
                    profiles = emptyList(),
                    providerConnections = emptyList(),
                    wallet = SettingsHomeWalletState(),
                    saveError = null,
                    onSetItem = { id, enabled -> config = config.withItem(id, enabled) },
                    onSelectWalletConnection = {}, onHideWalletAmounts = {}, onReadWallet = { _, _ -> }, onClearWallet = {}, onOpenRouter = {}, onBack = { back++ }
                )
            }
        }
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed()
        homeOption("tools").performClick()
        homeOption("privacy").performClick()
        assertEquals(5, config.selectedItems.size)
        homeOption("backup").assertIsNotEnabled().assertIsOff()
        homeOption("profiles").assertIsEnabled().assertIsOn().performClick()
        homeOption("backup").assertIsEnabled().performClick()
        assertEquals(5, config.selectedItems.size)
        config.selectedItems.toList().forEach { id -> homeOption(id).performClick() }
        assertEquals(0, config.selectedItems.size)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithTag("settings_home_empty", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        assertEquals(1, back)
    }

    private fun homeOption(id: String): SemanticsNodeInteraction {
        val tag = "home_toggle_$id"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-review/settings-home").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
