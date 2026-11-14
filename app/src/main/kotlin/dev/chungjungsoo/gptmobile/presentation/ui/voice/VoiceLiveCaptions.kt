package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceState

@Composable
internal fun VoiceLiveCaptions(state: VoiceState, openTranscript: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    val caption = remember(state.phase, state.transcript, state.reply, state.hasDraft) { VoiceCaption.from(state) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.testTag("voice_live_captions"),
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = .18f))
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE CAPTIONS", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(caption?.speaker ?: "Your conversation", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                }
                IconButton(onClick = openTranscript) { Icon(Icons.Rounded.Subtitles, "Open full transcript", tint = colors.primary) }
            }
            if (caption == null) {
                Text("Your words and replies will appear here.", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            } else {
                VoiceCaptionText(caption, compact)
            }
        }
    }
}

@Composable
private fun VoiceCaptionText(caption: VoiceCaption, compact: Boolean) {
    val scroll = rememberScrollState()
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    var following by remember(caption.speaker) { mutableStateOf(true) }
    var previousText by remember(caption.speaker) { mutableStateOf("") }
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
            if (position < previousPosition) following = false
            if (position >= maximum - 1) following = true
            previousPosition = position
        }
    }
    LaunchedEffect(caption.text, scroll.maxValue, following) {
        if (following) scroll.scrollTo(scroll.maxValue)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = if (caption.live) MaterialTheme.colorScheme.primary.copy(alpha = .06f) else MaterialTheme.colorScheme.surfaceContainer) {
        SelectionContainer {
            Text(
                caption.text,
                Modifier.fillMaxWidth().heightIn(min = lineHeight * 2 + 24.dp, max = lineHeight * (if (compact) 3 else 4) + 24.dp)
                    .nestedScroll(manualScroll).verticalScroll(scroll).padding(12.dp).testTag("voice_caption_text"),
                style = MaterialTheme.typography.bodyLarge,
                color = if (caption.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
