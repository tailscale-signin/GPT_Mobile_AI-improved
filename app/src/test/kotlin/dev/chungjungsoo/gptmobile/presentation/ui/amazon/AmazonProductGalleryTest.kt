package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AmazonProductGalleryTest {
    @Test fun swipesAdvanceOrGoBackWithoutWrappingOrReactingToSmallDrags() {
        assertEquals(1, amazonProductSwipeTarget(0, 3, -90f, 64f))
        assertEquals(0, amazonProductSwipeTarget(1, 3, 90f, 64f))
        assertEquals(1, amazonProductSwipeTarget(1, 3, 20f, 64f))
        assertEquals(0, amazonProductSwipeTarget(0, 3, 90f, 64f))
        assertEquals(2, amazonProductSwipeTarget(2, 3, -90f, 64f))
    }

    @Test fun darkThemeChartsUseThemedBackgroundTextAndDistinctColoredSeries() {
        val palette = AmazonGraphPalette(0xff101010.toInt(), 0xffeeeeee.toInt(), 0xff00aa77.toInt(), 0xff4488ff.toInt(), 0xffbb66ee.toInt(), 0xffff4444.toInt())
        assertEquals(palette.background, palette.color(0xffffffff.toInt()))
        assertEquals(palette.foreground, palette.color(0xff000000.toInt()))
        assertEquals(palette.primary, palette.color(0xff00ff00.toInt()))
        assertEquals(palette.secondary, palette.color(0xff0000ff.toInt()))
        assertNotEquals(palette.color(0xff00ff00.toInt()), palette.color(0xff0000ff.toInt()))
        assertEquals(0x40000000, palette.color(0x40000000) and -0x1000000)
    }
}
