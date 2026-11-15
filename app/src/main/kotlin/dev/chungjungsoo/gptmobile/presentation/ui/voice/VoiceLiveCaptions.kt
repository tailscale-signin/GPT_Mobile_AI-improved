package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import kotlin.math.roundToInt

@Composable
internal fun VoiceLiveCaptions(state: VoiceState, compact: Boolean, modifier: Modifier = Modifier) {
    val caption = remember(state.phase, state.transcript, state.reply, state.hasDraft) { VoiceCaption.from(state) }
    Column(modifier.testTag("voice_live_captions"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            caption?.speaker ?: "Live captions",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        key(caption?.speaker) {
            VoiceCaptionText(
                caption ?: VoiceCaption("Your words will appear here", "", false),
                state.spokenRange.takeIf { state.phase == VoicePhase.SPEAKING },
                compact,
                Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VoiceCaptionText(caption: VoiceCaption, spokenRange: IntRange?, compact: Boolean, modifier: Modifier) {
    val scroll = rememberScrollState()
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    val follow = remember(caption.speaker) { VoiceCaptionFollowing(scroll.value) }
    var following by remember(caption.speaker) { mutableStateOf(true) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fade = with(LocalDensity.current) { 12.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val observedRange = caption.highlightRange(spokenRange)
    val animated = voiceMotionEnabled()
    val text = remember(caption.text, observedRange, accent) {
        buildAnnotatedString {
            append(caption.text)
            if (observedRange != null) {
                // Changing font weight while speaking can rewrap the line underneath the reader.
                addStyle(SpanStyle(color = accent, background = accent.copy(alpha = .12f)), observedRange.first, observedRange.last + 1)
            }
        }
    }
    val manualScroll = remember(scroll, caption.speaker) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    follow.pause()
                    following = false
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) following = follow.userScroll(scroll.value, scroll.maxValue)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(scroll, caption.speaker) {
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (position, maximum) ->
            // Accessibility scroll actions can change ScrollState without a drag callback.
            following = follow.observe(position, maximum)
        }
    }
    LaunchedEffect(caption.text, observedRange, layout, scroll.maxValue, following, animated) {
        if (!following) return@LaunchedEffect
        // Wait for this text's layout before moving; the old maximum belongs to the prior phrase.
        val measured = layout?.takeIf { it.layoutInput.text.text == caption.text } ?: return@LaunchedEffect
        val position = observedRange?.first?.let { index ->
            val line = measured.getLineForOffset(index)
            val center = (measured.getLineTop(line) + measured.getLineBottom(line)) / 2
            (center + fade - scroll.viewportSize / 2f).roundToInt().coerceIn(0, scroll.maxValue)
        } ?: scroll.maxValue
        if (position == scroll.value) return@LaunchedEffect
        follow.beginAutomaticScroll()
        try {
            if (animated) scroll.animateScrollTo(position, tween(280)) else scroll.scrollTo(position)
        } finally {
            follow.endAutomaticScroll(scroll.value)
        }
    }
    SelectionContainer(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (size.height > fade * 2 && scroll.maxValue > 0) {
                    val fraction = fade / size.height
                    drawRect(
                        Brush.verticalGradient(
                            0f to if (scroll.value > 0) Color.Transparent else Color.Black,
                            fraction to Color.Black,
                            (1f - fraction) to Color.Black,
                            1f to if (scroll.value < scroll.maxValue) Color.Transparent else Color.Black
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().height(lineHeight * (if (compact) 3 else 4) + 24.dp)
                .nestedScroll(manualScroll).verticalScroll(scroll).padding(vertical = 12.dp).testTag("voice_caption_text"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = when {
                caption.speaker.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                caption.live -> accent
                else -> MaterialTheme.colorScheme.onSurface
            },
            onTextLayout = { layout = it }
        )
    }
}
