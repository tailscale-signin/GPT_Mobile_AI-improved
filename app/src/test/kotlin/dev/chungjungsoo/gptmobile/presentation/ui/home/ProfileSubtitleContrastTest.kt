package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSubtitleContrastTest {
    @Test fun subtitlesRemainReadableOnLightDarkAndMidToneSurfaces() {
        for (level in 0..20) {
            val background = Color(level / 20f, level / 20f, level / 20f)
            for (accent in listOf(Color.Cyan, Color.Yellow, Color.Magenta, Color.Gray)) {
                val foreground = profileSubtitleColor(accent, background).compositeOver(background).luminance()
                val surface = background.luminance()
                val contrast = (maxOf(foreground, surface) + 0.05f) / (minOf(foreground, surface) + 0.05f)
                assertTrue("Background $level must retain normal text contrast: $contrast", contrast >= 4.5f)
            }
        }
    }
}
