package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE, shadows = [ComposerBitmapShadow::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ComposerBitmapShadowTest {
    @Test fun sixArgumentAndroidFactoryRetainsBitmapMetadataPixelsAndRelease() {
        val metrics = DisplayMetrics().apply { densityDpi = 240 }
        val colorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val bitmap = Bitmap.createBitmap(metrics, 4, 3, Bitmap.Config.ARGB_8888, false, colorSpace)
        try {
            assertEquals(4, bitmap.width)
            assertEquals(3, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertEquals(colorSpace, bitmap.colorSpace)
            assertEquals(240, bitmap.density)
            assertFalse(bitmap.hasAlpha())
            assertTrue(bitmap.isMutable)
            bitmap.setPixel(1, 2, Color.RED)
            assertEquals(Color.RED, bitmap.getPixel(1, 2))
        } finally {
            bitmap.recycle()
        }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun composeImageBitmapUsesTheColoredFactoryWithNullDisplayMetrics() {
        val image = ImageBitmap(4, 3, ImageBitmapConfig.Argb8888, hasAlpha = true, colorSpace = ColorSpaces.DisplayP3)
        val bitmap = image.asAndroidBitmap()
        try {
            assertEquals(4, image.width)
            assertEquals(3, image.height)
            assertEquals(ImageBitmapConfig.Argb8888, image.config)
            assertEquals(ColorSpaces.DisplayP3, image.colorSpace)
            assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), bitmap.colorSpace)
            assertTrue(image.hasAlpha)
            assertTrue(bitmap.isMutable)
            bitmap.setPixel(1, 2, Color.BLUE)
            assertEquals(Color.BLUE, bitmap.getPixel(1, 2))
        } finally {
            bitmap.recycle()
        }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun sixArgumentFactoryRetainsDimensionAndMutableConfigValidation() {
        val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        assertThrows(IllegalArgumentException::class.java) {
            Bitmap.createBitmap(null, 0, 3, Bitmap.Config.ARGB_8888, true, colorSpace)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Bitmap.createBitmap(null, 4, 0, Bitmap.Config.ARGB_8888, true, colorSpace)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Bitmap.createBitmap(null, 4, 3, Bitmap.Config.HARDWARE, true, colorSpace)
        }
    }
}
