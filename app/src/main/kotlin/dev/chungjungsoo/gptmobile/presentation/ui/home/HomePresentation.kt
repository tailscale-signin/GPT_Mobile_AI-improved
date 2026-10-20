package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal fun plainProfileName(raw: String, fallback: String = "AI profile"): String {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(raw)
    val plain = buildString {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val cluster = raw.substring(start, end)
            val emoji = cluster.codePoints().anyMatch { cp ->
                UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION) || UCharacter.hasBinaryProperty(cp, UProperty.EXTENDED_PICTOGRAPHIC) || cp in 0x1F1E6..0x1F1FF || cp == 0x20E3
            }
            if (!emoji || '\uFE0E' in cluster) append(cluster) else append(' ')
            start = end
            end = iterator.next()
        }
    }.split(Regex("\\s+")).filterNot { it in setOf(":)", ":-)", ":(", ":-(", ";)", ";-)", ":D", ":-D", ":P", ":-P", "<3", "^_^", ":/", ":|") }.joinToString(" ").trim()
    return plain.ifBlank { if (fallback == "AI profile") fallback else plainProfileName(fallback) }
}

internal fun profileSubtitleColor(accent: Color, background: Color): Color {
    // Relative opacity reduction: 25% more transparent than the previous 25% opacity.
    return accent.copy(alpha = 0.25f * 0.75f)
}

@Composable
internal fun ProfileNameText(names: String, compact: Boolean) {
    val plain = remember(names) { plainProfileName(names) }
    Text(plain, color = profileSubtitleColor(MaterialTheme.colorScheme.primary, dev.chungjungsoo.gptmobile.presentation.common.appBaseBackground()), fontSize = 13.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun androidx.compose.ui.Modifier.homeRowFades(state: androidx.compose.foundation.lazy.LazyListState, enabled: Boolean): androidx.compose.ui.Modifier {
    val background = dev.chungjungsoo.gptmobile.presentation.common.appBaseBackground()
    return if (!enabled) {
        this
    } else {
        this.then(
            androidx.compose.ui.Modifier.drawWithContent {
                drawContent()
                val top = 8.dp.toPx().coerceAtMost(size.height / 8)
                if (state.canScrollBackward && top > 0) drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)), 0f, top), size = androidx.compose.ui.geometry.Size(size.width, top))
            }
        )
    }
}
