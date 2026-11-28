package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionCursor
import dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionTiming
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTranscript
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import kotlin.math.floor
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun VoiceLiveCaptions(state: VoiceState, compact: Boolean, modifier: Modifier = Modifier, cursor: StateFlow<VoiceCaptionCursor>? = null) {
    VoiceLiveCaptions(state, compact, modifier, cursor, replyOnly = false)
}

@Composable
internal fun VoiceLiveCaptions(state: VoiceState, compact: Boolean, modifier: Modifier, cursor: StateFlow<VoiceCaptionCursor>?, replyOnly: Boolean) {
    val liveCursor = cursor?.collectAsStateWithLifecycle()?.value?.takeIf { it.belongsTo(state.captionPresentationId) }
    val observed = if (liveCursor == null) state else state.copy(spokenRange = liveCursor.range, playbackCaption = liveCursor.projectedText)
    val caption = remember(observed.phase, observed.transcript, observed.reply, observed.playbackCaption, observed.hasDraft, observed.awaitingReply, observed.readingDetails, observed.replyComplete, replyOnly) {
        VoiceCaption.from(observed, replyOnly)
    }
    if (caption == null) return
    val speaking = state.phase == VoicePhase.SPEAKING
    val motion = voiceMotionEnabled()
    val opacity = remember(caption.speaker, state.captionPresentationId) { Animatable(if (speaking && motion) 0f else 1f) }
    LaunchedEffect(opacity, motion) {
        if (!motion) {
            opacity.snapTo(1f)
        } else if (opacity.value < 1f) {
            opacity.animateTo(1f, tween(2000))
        }
    }
    // Transport timing may arrive after a readable preview. Only a new presentation
    // replaces the reader, so the first playback ID cannot jump or erase that preview.
    key(caption.speaker, state.captionPresentationId) {
        VoiceCaptionText(
            caption,
            observed.spokenRange,
            speaking,
            compact,
            liveCursor?.timing,
            state.recognitionTranscript?.takeIf { caption.speaker == "You" && it.text == caption.text },
            modifier.graphicsLayer { alpha = opacity.value }.testTag("voice_live_captions").padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun VoiceCaptionText(caption: VoiceCaption, spokenRange: IntRange?, speaking: Boolean, compact: Boolean, timing: VoiceCaptionTiming?, recognition: VoiceRecognitionTranscript?, modifier: Modifier) {
    val wordCache = remember(caption.speaker) { VoiceCaptionWordCache() }
    val words = remember(caption.text) { wordCache.update(caption) }
    val active = when {
        speaking -> caption.highlightRange(spokenRange, words)
        caption.live && caption.speaker == "You" -> recognition?.timings?.lastOrNull()?.range ?: words.lastOrNull()
        else -> null
    }
    var lastSpoken by remember { mutableIntStateOf(-1) }
    if (speaking && active != null) SideEffect { lastSpoken = active.first }
    val base = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val focusStyle = base.copy(fontSize = base.fontSize * 2, lineHeight = base.lineHeight * 2, textAlign = TextAlign.Center)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fonts = LocalFontFamilyResolver.current
    val fontStyle = focusStyle.fontStyle ?: FontStyle.Normal
    val synthesis = focusStyle.fontSynthesis ?: FontSynthesis.All
    val normalTypeface by fonts.resolve(focusStyle.fontFamily, focusStyle.fontWeight ?: FontWeight.Normal, fontStyle, synthesis)
    val boldTypeface by fonts.resolve(focusStyle.fontFamily, FontWeight.Bold, fontStyle, synthesis)
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val accessibleText = remember(caption.text) { AnnotatedString(caption.text) }
    val animate = voiceMotionEnabled()
    var timingSnapshot by remember { mutableStateOf<VoiceRecognitionTranscript?>(null) }
    timingSnapshot?.let { VoiceRecognitionTimingsDialog(it) { timingSnapshot = null } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val layoutCache = remember(caption.speaker, focusStyle, measurer, width, normalTypeface, boldTypeface) { VoiceCaptionLayoutCache() }
        val lines = remember(caption.text, layoutCache) {
            layoutCache.update(caption.text) { tail ->
                val measured = measurer.measure(AnnotatedString(tail), style = focusStyle.copy(fontWeight = FontWeight.Bold), constraints = Constraints(maxWidth = width))
                List(measured.lineCount) { line -> VoiceCaptionLine(measured.getLineStart(line), measured.getLineEnd(line, visibleEnd = true)) }
            }
        }
        val lastLine = lines.lastIndex.coerceAtLeast(0)
        val offset = caption.focusOffset(active, lastSpoken, speaking)
        val liveLine = lines.lineForOffset(offset.coerceIn(0, caption.text.length))
        val lineHeight = with(density) { base.lineHeight.toPx() }
        val ribbonHeight = lineHeight * (2f * captionLineDisplacement(2f) + captionLineScale(2f))
        val scrollStep = lineHeight * 1.75f
        var reader by remember { mutableStateOf(VoiceCaptionReader()) }
        var returningToLive by remember { mutableStateOf(false) }
        val target = reader.position(liveLine, lastLine)
        val motion = remember { VoiceCaptionMotion(target) }
        val highlights = remember { VoiceCaptionHighlights() }
        val drawnPosition = remember { mutableFloatStateOf(target) }
        val frameNanos = remember { mutableLongStateOf(0L) }
        var previousLines by remember { mutableStateOf(emptyList<VoiceCaptionLine>()) }

        fun move(pixels: Float): Float {
            val before = drawnPosition.floatValue
            val next = (before + pixels / scrollStep).coerceIn(0f, lastLine.toFloat())
            if (next != before) {
                returningToLive = false
                reader = reader.scroll(next, lastLine)
                motion.seek(next, frameNanos.longValue)
                drawnPosition.floatValue = next
            }
            return (next - before) * scrollStep
        }

        val scrolling = rememberScrollableState(::move)
        LaunchedEffect(lines, target, active, speaking, animate, reader.following, returningToLive, timing) {
            var now = withFrameNanos { it }
            if (previousLines !== lines) {
                val current = motion.sample(now).position
                val mapped = captionReflowPosition(previousLines, lines, current)
                motion.reanchor(mapped - current)
                reader = reader.reanchor(mapped)
                previousLines = lines
            }
            val playbackMoving = when {
                !reader.following || returningToLive -> true
                caption.speaker == "AI reply" && lastSpoken >= 0 && !speaking -> false
                else -> timing?.playing != false
            }
            motion.playing(playbackMoving, now)
            val wordMs = timing?.let { captionWordMotionDurationMs(it.wordStartFrame, it.wordEndFrame, it.frameAt(now), it.sampleRate) } ?: 320
            motion.retarget(reader.position(liveLine, lastLine), now, animate && reader.following, wordMs)
            if (timing != null && reader.following && animate) motion.refreshClock(now, wordMs)
            val onset = timing?.let {
                captionWordOnsetNanos(it.wordStartFrame, it.wordEndFrame, it.presentedFrame, it.bufferedEndFrame, it.sampleRate, it.observedAtNanos, now, it.playing)
            } ?: now
            val wordNanos = timing?.takeIf { it.sampleRate > 0 && it.wordEndFrame > it.wordStartFrame }
                ?.let { (it.wordEndFrame - it.wordStartFrame) * 1_000_000_000L / it.sampleRate }
            highlights.playing(timing?.playing != false, now)
            highlights.update(active, onset, now, animate, wordNanos)
            if (timing != null) highlights.refreshClock(active, onset, now)
            do {
                frameNanos.longValue = now
                drawnPosition.floatValue = motion.sample(now).position.coerceIn(0f, lastLine.toFloat())
                if (!motion.moving(now) && !highlights.animating(now)) break
                now = withFrameNanos { it }
            } while (true)
            if (returningToLive) returningToLive = false
        }

        val visible by remember(lines) {
            derivedStateOf {
                val center = floor(drawnPosition.floatValue).toInt()
                (center - 3..center + 3).filter { it in lines.indices }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            SelectionContainer(
                Modifier.fillMaxWidth().clipToBounds()
                    .scrollable(scrolling, Orientation.Vertical, reverseDirection = true)
                    .clearAndSetSemantics {
                        testTag = "voice_caption_text"
                        text = accessibleText
                        if (caption.speaker.isNotEmpty()) stateDescription = caption.speaker
                        verticalScrollAxisRange = ScrollAxisRange(
                            value = { drawnPosition.floatValue * scrollStep },
                            maxValue = { lastLine * scrollStep }
                        )
                        scrollBy { _, y -> move(y) != 0f }
                    }
            ) {
                Box(Modifier.fillMaxWidth().height(with(density) { ribbonHeight.toDp() }).clipToBounds()) {
                    for (index in visible) {
                        val line = lines[index]
                        key(line.start, normalTypeface, boldTypeface) {
                            VoiceCaptionDrawnLine(
                                text = caption.text.substring(line.start, line.end),
                                sourceStart = line.start,
                                style = focusStyle,
                                width = width,
                                measurer = measurer,
                                ink = ink,
                                accent = accent,
                                highlights = highlights,
                                nowNanos = { frameNanos.longValue },
                                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                                    .height(with(density) { (lineHeight * 2).toDp() })
                                    .graphicsLayer {
                                        val distance = index - drawnPosition.floatValue
                                        val scale = captionLineScale(distance) / 2f
                                        scaleX = scale
                                        scaleY = scale
                                        translationY = captionLineDisplacement(distance) * lineHeight
                                        alpha = captionLineAlpha(distance)
                                    }
                            )
                        }
                    }
                }
            }
            // Reserve a touch-sized footer so entering manual reading never shifts the ribbon.
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                if (!reader.following) {
                    TextButton(
                        onClick = {
                            returningToLive = true
                            reader = VoiceCaptionReader()
                        },
                        modifier = Modifier.testTag("voice_caption_return_to_live"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Return to live", color = accent, style = MaterialTheme.typography.labelLarge)
                    }
                } else if (!recognition?.timings.isNullOrEmpty()) {
                    TextButton(onClick = { timingSnapshot = recognition }, modifier = Modifier.testTag("voice_recognition_show_timing")) {
                        Text("Timing", color = accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
