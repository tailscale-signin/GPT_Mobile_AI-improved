package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Android/Compose layout and native graphics; no emulator or replacement UI is used. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w600dp-h2200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeRenderTest {
    @get:Rule val compose = createComposeRule()

    private val points = listOf(
        SettingsHomePoint("wallet", "OpenRouter Wallet", "$6.99999999 USD", "Updated just now · Account", SettingsHomeTone.POSITIVE),
        SettingsHomePoint("enabled", "Selected AI profile", "Enabled", "No recorded request", SettingsHomeTone.POSITIVE, "Not checked", SettingsHomeTone.UNKNOWN),
        SettingsHomePoint("disabled", "Another AI profile", "Disabled", "Last update 1 h ago", SettingsHomeTone.NEGATIVE, "Stale result", SettingsHomeTone.STALE),
        SettingsHomePoint("tools", "Plugins & Tools", "4 configured connections", "Individual tool permissions still apply"),
        SettingsHomePoint("privacy", "Privacy choices", "Diagnostics off", "Local Assist off · location tool allowed")
    )

    @Test fun compactOneToFiveItemsHaveNoTextOverflowOrOverlappingTiles() = renderCounts("compact-light", 320, 1f)

    @Test fun wideOneToFiveItemsHaveNoTextOverflowOrOverlappingTiles() = renderCounts("wide-light", 560, 1f)

    @Test fun largeFontRtlOneToFiveItemsHaveNoTextOverflowOrOverlappingTiles() = renderCounts("large-rtl-dark", 320, 2f, dark = true, rtl = true)

    private fun renderCounts(name: String, width: Int, fontScale: Float, dark: Boolean = false, rtl: Boolean = false) {
        var count by mutableStateOf(0)
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(width.dp).padding(horizontal = 16.dp)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            SettingsHomePanel(points.take(count), onClick = { opened++ })
                        }
                    }
                }
            }
        }
        // The clickable panel merges its text for accessibility. Inspect the actual child
        // when checking its visibility, as we do for the individual information tiles.
        compose.onNodeWithTag("settings_home_empty", useUnmergedTree = true).assertIsDisplayed()
        for (size in 1..5) {
            compose.runOnIdle { count = size }
            val bubble = compose.onNodeWithTag("settings_home_bubble").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Settings viewport and its side padding must be used", (width - 32).toFloat(), bubble.width, 1f)
            val tiles = points.take(size).map { point ->
                val bounds = compose.onNodeWithTag("settings_home_point_${point.id}", useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("${point.id} must have usable dimensions", bounds.width > 0 && bounds.height > 0)
                assertTrue("${point.id} must remain inside its bubble", bounds.left >= bubble.left && bounds.right <= bubble.right && bounds.top >= bubble.top && bounds.bottom <= bubble.bottom)
                bounds
            }
            tiles.forEachIndexed { index, tile ->
                tiles.drop(index + 1).forEach { other -> assertFalse("Tiles must not overlap", tile.overlaps(other)) }
            }
            val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Real text must be laid out", textNodes.isNotEmpty())
            compose.runOnIdle {
                textNodes.forEach { node ->
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
                    assertTrue(results.isNotEmpty())
                    results.forEach { layout ->
                        assertTextFitsRenderedBounds(layout, node.boundsInRoot)
                    }
                }
            }
        }
        capture(name)
        compose.onNodeWithTag("settings_home_bubble").performClick()
        compose.runOnIdle { assertEquals("The bubble opens Home configuration", 1, opened) }
    }

    private fun assertTextFitsRenderedBounds(layout: TextLayoutResult, bounds: Rect) {
        val input = layout.layoutInput
        assertEquals("Text width must remain visible: ${input.text}", layout.size.width.toFloat(), bounds.width, 1f)
        assertEquals("Text height must remain visible: ${input.text}", layout.size.height.toFloat(), bounds.height, 1f)
        // Compose 1.12.1's String-text semantics helper pairs the actual wrap-content size
        // with a paragraph using the larger parent maxWidth (for example, 73 versus 424 px
        // for "Home tab"). Its width-overflow flag therefore does not measure clipping.
        // Reuse its text/style/fonts, constrained to the actual rendered element instead.
        val measured = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
            text = input.text,
            style = input.style,
            overflow = input.overflow,
            softWrap = input.softWrap,
            maxLines = input.maxLines,
            placeholders = input.placeholders,
            constraints = Constraints(maxWidth = layout.size.width, maxHeight = layout.size.height)
        )
        assertFalse("Text must wrap without clipping: ${input.text}", measured.hasVisualOverflow)
        assertTrue("Text must contain a laid-out line: ${input.text}", measured.lineCount > 0)
        assertEquals("Every character must remain visible: ${input.text}", input.text.length, measured.getLineEnd(measured.lineCount - 1))
        for (line in 0 until measured.lineCount) {
            assertFalse("Text must not be ellipsized: ${input.text}", measured.isLineEllipsized(line))
            assertTrue("Text must fit its rendered width: ${input.text}", measured.getLineLeft(line) >= -1f && measured.getLineRight(line) <= layout.size.width + 1f)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun homeScreenEnforcesFiveAccessibleSelectionsAndAllowsAnEmptyState() {
        var config by mutableStateOf(SettingsHomeConfiguration())
        var back = 0
        compose.setContent {
            MaterialTheme {
                SettingsHomeScreen(
                    configuration = config,
                    points = points.filter { it.id in config.selectedItems },
                    profiles = emptyList(),
                    providerConnections = emptyList(),
                    wallet = SettingsHomeWalletState(),
                    saveError = null,
                    onSetItem = { id, enabled -> config = config.withItem(id, enabled) },
                    onSelectWalletConnection = {}, onHideWalletAmounts = {}, onReadWallet = { _, _ -> },
                    onClearWallet = {}, onOpenRouter = {}, onBack = { back++ }
                )
            }
        }
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed()
        homeOption("tools").performClick()
        homeOption("privacy").performClick()
        compose.runOnIdle { assertEquals(5, config.selectedItems.size) }
        homeOption("backup").assertIsNotEnabled().assertIsOff()
        homeOption("profiles").assertIsEnabled().assertIsOn().performClick()
        homeOption("backup").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(5, config.selectedItems.size) }
        val remaining = compose.runOnIdle { config.selectedItems.toList() }
        remaining.forEach { id -> homeOption(id).performClick() }
        compose.runOnIdle { assertEquals(0, config.selectedItems.size) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithTag("settings_home_empty", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    private fun homeOption(id: String): SemanticsNodeInteraction {
        // Ask the lazy container to compose offscreen options before querying the row.
        val tag = "home_toggle_$id"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("settings_home_bubble").captureToImage().asAndroidBitmap()
        assertTrue("Native renderer must produce nonempty pixels", bitmap.width > 0 && bitmap.height > 0)
        val sampledColors = buildSet {
            for (x in 0 until bitmap.width step 3) {
                for (y in 0 until bitmap.height step 3) add(bitmap.getPixel(x, y))
            }
        }
        assertTrue("A blank or placeholder capture is not a rendered screenshot", sampledColors.size > 12)
        val directory = File("build/reports/settings-home-render").apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        println("Settings Home native render: ${file.absolutePath}")
    }
}
