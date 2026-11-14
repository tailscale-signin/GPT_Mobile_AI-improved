package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import kotlin.math.roundToInt

@Composable
internal fun VoiceLiveCaptions(state: VoiceState, compact: Boolean, modifier: Modifier = Modifier) {
    val caption = remember(state.phase, state.transcript, state.reply, state.hasDraft) { VoiceCaption.from(state) }
    if (caption != null) VoiceCaptionText(caption, state.spokenRange.takeIf { caption.speaker == "AI reply" }, compact, modifier.testTag("voice_live_captions"))
}

@Composable
private fun VoiceCaptionText(caption: VoiceCaption, spokenRange: IntRange?, compact: Boolean, modifier: Modifier) {
    val scroll = rememberScrollState()
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    val aligned by rememberUpdatedState(spokenRange != null)
    var following by remember(caption.speaker) { mutableStateOf(true) }
    var previousText by remember(caption.speaker) { mutableStateOf("") }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fade = with(LocalDensity.current) { 12.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val text = remember(caption.text, spokenRange, accent) {
        buildAnnotatedString {
            append(caption.text)
            if (spokenRange != null && spokenRange.first >= 0 && spokenRange.last < caption.text.length) {
                addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), spokenRange.first, spokenRange.last + 1)
            }
        }
    }
    val manualScroll = remember(scroll, caption.speaker) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) following = scroll.value >= scroll.maxValue - 1
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(caption.text) {
        if (!caption.text.startsWith(previousText)) following = true
        previousText = caption.text
    }
    LaunchedEffect(scroll, caption.speaker) {
        var previousPosition = scroll.value
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (position, maximum) ->
            // Accessibility scroll actions can change ScrollState without a drag callback.
            if (position < previousPosition && !aligned) following = false
            if (position >= maximum - 1) following = true
            previousPosition = position
        }
    }
    LaunchedEffect(caption.text, spokenRange, layout, scroll.maxValue, following) {
        if (!following) return@LaunchedEffect
        val position = layout?.takeIf { it.layoutInput.text.text == caption.text }?.let { measured ->
            spokenRange?.first?.takeIf { it in caption.text.indices }?.let { index ->
                val line = measured.getLineForOffset(index)
                val center = (measured.getLineTop(line) + measured.getLineBottom(line)) / 2
                (center + fade - scroll.viewportSize / 2f).roundToInt().coerceIn(0, scroll.maxValue)
            }
        }
        if (position == null) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(position)
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
            Modifier.fillMaxWidth().heightIn(min = lineHeight * 2 + 24.dp, max = lineHeight * (if (compact) 3 else 4) + 24.dp)
                .nestedScroll(manualScroll).verticalScroll(scroll).padding(vertical = 12.dp).testTag("voice_caption_text"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            onTextLayout = { layout = it }
        )
    }
}
