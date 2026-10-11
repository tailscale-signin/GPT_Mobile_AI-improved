package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Paired semantic palettes are additive. A null family continues to render the legacy theme. */
internal object RenovationPalettes {
    val names = listOf("Forest", "Ocean", "Iris", "Amber", "Graphite")

    fun scheme(name: String, dark: Boolean): ColorScheme {
        val accents = when (name) {
            "Ocean" -> 0xFF245C9EL to 0xFFA4C5FFL
            "Iris" -> 0xFF6941A5L to 0xFFD3B9F4L
            "Amber" -> 0xFF7F571FL to 0xFFF0C69AL
            "Graphite" -> 0xFF4D5F57L to 0xFFD5DCD7L
            else -> 0xFF176B54L to 0xFF98D9B6L
        }
        val primary = Color(if (dark) accents.second else accents.first)
        val background = Color(if (dark) 0xFF111A17 else 0xFFF6F8F7)
        val surface = Color(if (dark) 0xFF1A2621 else 0xFFFFFFFF)
        val text = Color(if (dark) 0xFFE7F0EB else 0xFF17231F)
        val muted = Color(if (dark) 0xFFB6C7BD else 0xFF52635B)
        val container = if (name == "Forest") Color(if (dark) 0xFF224C38 else 0xFFD2EBDD) else lerp(surface, primary, if (dark) .20f else .13f)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary, onPrimary = if (dark) Color(0xFF083824) else Color.White,
            primaryContainer = container, onPrimaryContainer = if (dark) primary else Color(accents.first),
            secondary = muted, onSecondary = if (dark) Color(0xFF111A17) else Color.White,
            secondaryContainer = Color(if (dark) 0xFF293D33 else 0xFFE3EBE7), onSecondaryContainer = text,
            tertiary = primary, onTertiary = if (dark) Color(0xFF111A17) else Color.White,
            tertiaryContainer = container, onTertiaryContainer = text,
            background = background, onBackground = text,
            surface = surface, onSurface = text,
            surfaceVariant = Color(if (dark) 0xFF293D33 else 0xFFE3EBE7), onSurfaceVariant = muted,
            surfaceTint = primary,
            surfaceDim = background, surfaceBright = Color(if (dark) 0xFF293D33 else 0xFFFFFFFF),
            surfaceContainerLowest = background,
            surfaceContainerLow = Color(if (dark) 0xFF202F28 else 0xFFEDF2F0),
            surfaceContainer = surface,
            surfaceContainerHigh = Color(if (dark) 0xFF293D33 else 0xFFE3EBE7),
            surfaceContainerHighest = Color(if (dark) 0xFF30453B else 0xFFD8E3DC),
            outline = Color(if (dark) 0xFF8B9F94 else 0xFF687E72),
            outlineVariant = Color(if (dark) 0xFF465B50 else 0xFFC7D3CD),
            error = Color(if (dark) 0xFFFFB4AB else 0xFFB3261E),
            onError = Color(if (dark) 0xFF690005 else 0xFFFFFFFF),
            errorContainer = Color(if (dark) 0xFF93000A else 0xFFFFDAD6),
            onErrorContainer = Color(if (dark) 0xFFFFDAD6 else 0xFF410002),
            inverseSurface = text, inverseOnSurface = background,
            inversePrimary = Color(if (dark) accents.first else accents.second), scrim = Color.Black
        )
    }

    fun withPrimary(base: ColorScheme, primary: Color): ColorScheme {
        val foreground = if (contrastRatio(Color.White, primary) >= contrastRatio(Color.Black, primary)) Color.White else Color.Black
        val container = lerp(base.surface, primary, .16f)
        return base.copy(primary = primary, onPrimary = foreground, primaryContainer = container, onPrimaryContainer = base.onSurface, surfaceTint = primary)
    }
}
