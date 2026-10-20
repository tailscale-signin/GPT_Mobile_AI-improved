package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropBox
import dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropHost
import dev.chungjungsoo.gptmobile.presentation.common.LocalGlassBackdropActive
import dev.chungjungsoo.gptmobile.presentation.common.LocalOverlayBlur
import dev.chungjungsoo.gptmobile.presentation.common.LocalOverlayMaterial
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdropSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses the production viewport capture/fade order, not an unrelated full-screen source. */
@SdkSuppress(minSdkVersion = 32)
class ConversationGlassInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)
    private var backdropEnabled by mutableStateOf(true)
    private var edgeFades by mutableStateOf(true)
    private var pattern by mutableStateOf(Pattern.RED)
    private var contentVisible by mutableStateOf(true)

    @Test fun conversationGlassKeepsRequestedOpacityOverFadedMessages() {
        showConversation()
        for (darkTheme in listOf(false, true)) {
            compose.runOnIdle {
                dark = darkTheme
                pattern = Pattern.RED
            }
            val red = samples()
            compose.runOnIdle { pattern = Pattern.BLUE }
            val blue = samples()
            for ((tag, transparency) in listOf("chat_input_glass" to 0.15, "chat_latest_glass" to 0.50)) {
                // The plate color cancels out. A red -> blue background changes R-B
                // by 2 * transparency; an opaque/faded/missing source changes nothing.
                val transmission = (red.getValue(tag).average() - blue.getValue(tag).average()) / 2.0
                assertEquals("$tag dark=$darkTheme must transmit the requested backdrop fraction", transparency, transmission, 0.045)
                println("CONVERSATION_GLASS_OPACITY tag=$tag dark=$darkTheme transmission=$transmission")
            }
            saveScreenshot("conversation-glass-opacity-${if (darkTheme) "dark" else "light"}.png")
        }
    }

    @Test fun conversationGlassDoesNotRevealMessagesBeforeEntryRestores() {
        contentVisible = false
        showConversation()
        val hiddenRed = samples()
        compose.runOnIdle { pattern = Pattern.BLUE }
        val hiddenBlue = samples()
        for (tag in hiddenRed.keys) {
            assertEquals("$tag must not reveal an unrestored message list", hiddenRed.getValue(tag).average(), hiddenBlue.getValue(tag).average(), 0.01)
        }
        compose.runOnIdle {
            contentVisible = true
            pattern = Pattern.RED
        }
        val visibleRed = samples()
        compose.runOnIdle { pattern = Pattern.BLUE }
        val visibleBlue = samples()
        for (tag in visibleRed.keys) {
            val transmission = (visibleRed.getValue(tag).average() - visibleBlue.getValue(tag).average()) / 2.0
            assertTrue("$tag must resume sampling messages when restoration finishes: $transmission", transmission > 0.10)
        }
    }

    @Test fun conversationGlassFiltersDetailInBothControls() {
        edgeFades = false
        pattern = Pattern.STRIPES
        backdropEnabled = false
        showConversation()
        for (darkTheme in listOf(false, true)) {
            compose.runOnIdle {
                dark = darkTheme
                backdropEnabled = false
            }
            val sharp = samples()
            saveScreenshot("conversation-glass-sharp-${if (darkTheme) "dark" else "light"}.png")
            compose.runOnIdle { backdropEnabled = true }
            val filtered = samples()
            saveScreenshot("conversation-glass-blur-${if (darkTheme) "dark" else "light"}.png")
            for (tag in sharp.keys) {
                val before = variance(sharp.getValue(tag))
                val after = variance(filtered.getValue(tag))
                assertTrue("$tag dark=$darkTheme fixture must contain visible stripes: $before", before > 0.002)
                assertTrue("$tag dark=$darkTheme must filter background detail: sharp=$before blur=$after", after < before * 0.4)
                println("CONVERSATION_GLASS_BLUR tag=$tag dark=$darkTheme sharp=$before blurred=$after")
            }
        }
    }

    private fun showConversation() {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                // Chat controls must blur even when menu blur is disabled. The sharp
                // reference below disables only the consumers' backdrop availability.
                CompositionLocalProvider(LocalOverlayMaterial provides "glass", LocalOverlayBlur provides "off") {
                    GlassBackdropHost {
                        // An unrelated outgoing destination must not contaminate this chat.
                        Canvas(Modifier.fillMaxSize().glassBackdropSource()) { drawRect(Color.Green) }
                        GlassBackdropBox(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
                            val density = LocalDensity.current
                            var composerHeight by remember { mutableStateOf(88.dp) }
                            // Keep the sampled patch more than a blur radius from the source
                            // edge, so missing off-screen pixels do not skew opacity measurements.
                            val dockInset = 80.dp
                            Canvas(
                                Modifier.fillMaxSize().chatMessageBackdrop(
                                    topFadeStart = 0.dp,
                                    topFade = 0.dp,
                                    bottomFadeStartFromBottom = if (edgeFades) composerHeight + dockInset else 0.dp,
                                    bottomFadeEndFromBottom = if (edgeFades) composerHeight * 0.5f + dockInset else 0.dp,
                                    contentVisible = contentVisible
                                )
                            ) {
                                when (pattern) {
                                    Pattern.RED -> drawRect(Color.Red)
                                    Pattern.BLUE -> drawRect(Color.Blue)
                                    Pattern.STRIPES -> for (x in 0..size.width.toInt() step 4) {
                                        drawRect(if ((x / 4) % 2 == 0) Color.Red else Color.Blue, Offset(x.toFloat(), 0f), Size(4f, size.height))
                                    }
                                }
                            }
                            // Keep the source Canvas active in both captures. Switching a
                            // menu preference cannot disable these forced-glass controls;
                            // an unavailable consumer backdrop gives an equally tinted,
                            // genuinely sharp reference without changing production code.
                            CompositionLocalProvider(LocalGlassBackdropActive provides backdropEnabled) {
                                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + dockInset + 12.dp)) {
                                    ScrollToBottomButton { }
                                }
                                ChatInputBox(
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = dockInset).onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
                                    // Leave a blank central patch to measure material, not glyphs.
                                    inputState = rememberTextFieldState(" ")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun samples(): Map<String, List<Double>> {
        compose.waitForIdle()
        return listOf("chat_input_glass", "chat_latest_glass").associateWith { tag ->
            val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
            val composer = tag == "chat_input_glass"
            val values = mutableListOf<Double>()
            val left = (pixels.width * 0.35f).toInt()
            val right = (pixels.width * 0.65f).toInt()
            val top = (pixels.height * if (composer) 0.60f else 0.18f).toInt()
            val bottom = (pixels.height * if (composer) 0.80f else 0.30f).toInt()
            for (y in top until bottom) {
                for (x in left until right) {
                    val color = pixels[x, y]
                    values += (color.red - color.blue).toDouble()
                }
            }
            values
        }
    }

    private fun variance(values: List<Double>): Double {
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private enum class Pattern { RED, BLUE, STRIPES }
}
