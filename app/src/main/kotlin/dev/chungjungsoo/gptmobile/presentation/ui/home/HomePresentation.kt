package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.delay

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
    fun contrast(color: Color): Float {
        val a = color.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
    val readable = if (contrast(accent) >= 4.5f) {
        accent
    } else if (contrast(Color.White) >= contrast(Color.Black)) {
        Color.White
    } else {
        Color.Black
    }
    for (step in 55..100) {
        val color = readable.copy(alpha = step / 100f)
        if (contrast(color) >= 4.5f) return color
    }
    return readable
}

@Composable
internal fun ProfileNameText(names: String, compact: Boolean) {
    val plain = remember(names) { plainProfileName(names) }
    val color = profileSubtitleColor(MaterialTheme.colorScheme.primary, dev.chungjungsoo.gptmobile.presentation.common.appBaseBackground())
    Text(plain, modifier = Modifier.fillMaxWidth(), color = color.copy(alpha = color.alpha * 0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Motion decorates measured milestones; it never advances the displayed amount of work. */
@Composable
internal fun ConversationProgressBar(work: ConversationWorkProgress?, animate: Boolean) {
    var displayed by remember { mutableStateOf<ConversationWorkProgress?>(null) }
    var activeTurn by remember { mutableStateOf<Int?>(null) }
    var completionPending by remember { mutableStateOf(false) }
    LaunchedEffect(work) {
        if (work?.running == true) {
            activeTurn = work.userMessageId
            completionPending = true
            displayed = work
        } else if (work?.fraction == 1f && completionPending && work.userMessageId == activeTurn) {
            // Run release and durable delivery may arrive in separate flow emissions.
            // A transient null hides activity immediately without losing this one flash.
            completionPending = false
            displayed = work
            delay(450)
            displayed = null
        } else {
            displayed = null
        }
    }
    val visible = displayed ?: return
    val color = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.secondary
    val fraction = visible.fraction.coerceIn(0f, if (visible.running) 0.95f else 1f)
    val pan = if (animate && visible.running) {
        val transition = rememberInfiniteTransition(label = "conversationProgress")
        val value by transition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "conversationProgressPan"
        )
        value
    } else {
        0.5f
    }
    val progressSemantics = if (fraction == 0f) Modifier.progressSemantics() else Modifier.progressSemantics(fraction)
    Canvas(
        Modifier.fillMaxWidth().height(3.dp).then(progressSemantics).semantics { stateDescription = visible.label }
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color.copy(alpha = 0.12f), cornerRadius = radius)
        val width = if (fraction == 0f) size.width else size.width * fraction
        val center = pan * width
        val band = width * 0.3f
        clipRect(right = width) {
            if (fraction > 0f) drawRoundRect(color.copy(alpha = 0.72f), size = Size(width, size.height), cornerRadius = radius)
            if (visible.running) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(color.copy(alpha = 0f), highlight, color.copy(alpha = 0f)),
                        start = Offset(center - band, 0f),
                        end = Offset(center + band, 0f)
                    ),
                    size = Size(width, size.height),
                    cornerRadius = radius
                )
            }
        }
    }
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
