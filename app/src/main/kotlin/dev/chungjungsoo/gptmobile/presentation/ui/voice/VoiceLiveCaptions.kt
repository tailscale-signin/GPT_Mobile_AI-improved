package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun VoiceLiveCaptions(state: VoiceState, compact: Boolean, modifier: Modifier = Modifier) {
    val caption = remember(state.phase, state.transcript, state.reply, state.playbackCaption, state.hasDraft) {
        VoiceCaption.from(state)
    }
    if (caption == null) return
    val speaking = state.phase == VoicePhase.SPEAKING
    val motion = voiceMotionEnabled()
    val opacity = remember(speaking, motion) { Animatable(if (speaking && motion) 0f else 1f) }
    LaunchedEffect(opacity) {
        if (opacity.value < 1f) opacity.animateTo(1f, tween(2000))
    }
    key(caption.speaker) {
        VoiceCaptionText(
            caption,
            state.spokenRange,
            state.phase == VoicePhase.SPEAKING,
            compact,
            modifier.graphicsLayer { alpha = opacity.value }.testTag("voice_live_captions").padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun VoiceCaptionText(caption: VoiceCaption, spokenRange: IntRange?, speaking: Boolean, compact: Boolean, modifier: Modifier) {
    val words = remember(caption.text) { caption.wordRanges() }
    val active = when {
        speaking -> caption.highlightRange(spokenRange, words)
        caption.live && caption.speaker == "You" -> words.lastOrNull()
        else -> null
    }
    var lastSpoken by remember(speaking) { mutableIntStateOf(0) }
    if (speaking && active != null) SideEffect { lastSpoken = active.first }
    val base = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val focusStyle = base.copy(fontSize = base.fontSize * 2, lineHeight = base.lineHeight * 2, textAlign = TextAlign.Center)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val animate = voiceMotionEnabled()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val layout = remember(caption.text, focusStyle, measurer, width) {
            measurer.measure(AnnotatedString(caption.text), style = focusStyle.copy(fontWeight = FontWeight.Bold), constraints = Constraints(maxWidth = width))
        }
        val lastLine = layout.lineCount - 1
        val offset = active?.first ?: if (speaking) lastSpoken else caption.text.length
        val liveLine = layout.getLineForOffset(offset.coerceIn(0, caption.text.length))
        val step = with(density) { focusStyle.lineHeight.toPx() }
        var reader by remember { mutableStateOf(VoiceCaptionReader()) }
        val position = reader.position(liveLine, lastLine)
        val center = position.roundToInt()
        fun move(pixels: Float): Float {
            // A drag can deliver multiple deltas before the next composition.
            val before = reader.position(liveLine, lastLine)
            val next = (before + pixels / step).coerceIn(0f, lastLine.toFloat())
            if (next != before) reader = reader.scroll(next, lastLine)
            return (next - before) * step
        }
        val scrolling = rememberScrollableState(::move)
        val travel = remember { Animatable(0f) }
        var previous by remember { mutableIntStateOf(center) }
        LaunchedEffect(center, animate) {
            val distance = center - previous
            previous = center
            if (animate && reader.following && abs(distance) == 1) {
                travel.snapTo(distance.toFloat())
                travel.animateTo(0f, tween(170))
            } else {
                travel.snapTo(0f)
            }
        }
        SelectionContainer(
            Modifier.fillMaxWidth().clipToBounds()
                .scrollable(scrolling, Orientation.Vertical, reverseDirection = true)
                .clearAndSetSemantics {
                    testTag = "voice_caption_text"
                    text = AnnotatedString(caption.text)
                    if (caption.speaker.isNotEmpty()) stateDescription = caption.speaker
                    verticalScrollAxisRange = ScrollAxisRange(
                        value = { reader.position(liveLine, lastLine) * step },
                        maxValue = { lastLine * step }
                    )
                    scrollBy { _, y -> move(y) != 0f }
                }
        ) {
            Column(Modifier.fillMaxWidth()) {
                for (slot in -2..2) {
                    val line = center + slot
                    val scale = captionLineScale(slot)
                    val style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
                    val height = with(density) { style.lineHeight.toDp() }
                    Box(
                        Modifier.fillMaxWidth().height(height).graphicsLayer { translationY = travel.value * step * .75f },
                        contentAlignment = Alignment.Center
                    ) {
                        if (line in 0..lastLine) {
                            val start = layout.getLineStart(line)
                            val end = layout.getLineEnd(line, visibleEnd = true)
                            val highlight = if (slot == 0) active else null
                            val annotated = remember(caption.text, start, end, highlight, accent) {
                                buildAnnotatedString {
                                    append(caption.text, start, end)
                                    if (highlight != null) {
                                        val from = maxOf(start, highlight.first)
                                        val to = minOf(end, highlight.last + 1)
                                        if (from < to) addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), from - start, to - start)
                                    }
                                }
                            }
                            Text(annotated, style = style, color = ink.copy(alpha = captionLineAlpha(slot)), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
