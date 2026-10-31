package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel evidence distinguishes backdrop filtering from an unchanged translucent tint. */
@SdkSuppress(minSdkVersion = 32)
class GlassBackdropInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsBackdropAndTypographyChangesKeepNavigationContentComposed() {
        var settings by mutableStateOf(false)
        val allocations = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {
            MaterialTheme {
                GlassBackdropHost {
                    SettingsGlassBackdropHost(settings) {
                        dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsTypographyScope(enabled = settings) {
                            val contentIdentity = remember { allocations.incrementAndGet() }
                            if (!settings) {
                                Canvas(Modifier.fillMaxSize().captureHomeGlassBackdrop()) { drawRect(Color.Blue) }
                            }
                            Text("Navigation content $contentIdentity")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, allocations.get())
        repeat(3) {
            compose.runOnIdle { settings = true }
            compose.waitForIdle()
            assertEquals("Opening settings must preserve remembered navigation content", 1, allocations.get())
            compose.runOnIdle { settings = false }
            compose.waitForIdle()
            assertEquals("Returning home must preserve remembered navigation content", 1, allocations.get())
        }
    }

    @Test fun openingSettingsWhileHomeBlurStillDrawsDoesNotReplayLiveHomeLayers() {
        var settings by mutableStateOf(false)
        var outgoingHome by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOverlayMaterial provides "glass", LocalOverlayBlur provides "auto") {
                    GlassBackdropHost {
                        SettingsGlassBackdropHost(settings) {
                            // NavHost retains the outgoing destination during its exit animation.
                            // Include HomeScreen's nested source and floating glass control: a
                            // plain Canvas fixture cannot detect a circular blur dependency.
                            if (outgoingHome) {
                                Box(Modifier.fillMaxSize().captureHomeGlassBackdrop()) {
                                    Canvas(Modifier.fillMaxSize().glassBackdropSource()) {
                                        drawRect(Color.Blue)
                                        drawCircle(Color.Red, radius = size.minDimension / 3f)
                                    }
                                    AppGlassSurface(Modifier.size(72.dp)) { Text("New chat") }
                                }
                            }
                            if (settings) Text("Settings", Modifier.testTag("settings_destination"))
                        }
                    }
                }
            }
        }
        repeat(3) {
            compose.waitForIdle()
            compose.runOnIdle { settings = true }
            compose.waitForIdle()
            compose.onNodeWithTag("settings_destination").assertIsDisplayed()
            // Remove Home only after Settings has drawn with the outgoing glass control.
            compose.runOnIdle { outgoingHome = false }
            compose.waitForIdle()
            compose.onNodeWithTag("settings_destination").assertIsDisplayed()
            compose.runOnIdle {
                settings = false
                outgoingHome = true
            }
        }
    }

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
            assertFixtureWindowIsActive()
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

    @Test fun stackedDialogSamplesItsVisibleParentInsteadOfOnlyTheActivity() {
        var child by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalOverlayMaterial provides "glass", LocalOverlayBlur provides "auto") {
                    GlassBackdropHost {
                        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Blue) }
                        // The child is composed before its existing parent to exercise retained
                        // opening order rather than relying on source traversal order.
                        if (child) FadingAlertDialog({}, { Text("Close") }, text = { Box(Modifier.height(120.dp)) })
                        FadingDialog({}) {
                            AppGlassSurface(Modifier.height(300.dp).fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize()) { drawRect(Color.Green) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { child = true }
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.filesDir, "glass-stacked.png").outputStream().use {
            check(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        assertFixtureWindowIsActive()
        val center = pixels.getPixel(pixels.width / 2, pixels.height / 2)
        pixels.recycle()
        val greenOverBlue = (android.graphics.Color.green(center) - android.graphics.Color.blue(center)) / 255.0
        assertTrue("The child must sample its green parent, not the blue activity: green-blue=$greenOverBlue", greenOverBlue > 0.03)
    }

    private fun assertFixtureWindowIsActive() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val foregroundPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        assertEquals("A system/foreign window is obstructing the fixture; inspect the retained screenshot before diagnosing app pixels", instrumentation.targetContext.packageName, foregroundPackage)
    }
}
