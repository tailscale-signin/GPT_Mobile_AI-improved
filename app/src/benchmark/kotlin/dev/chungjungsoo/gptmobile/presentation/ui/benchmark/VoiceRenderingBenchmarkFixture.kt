package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionCursor
import dev.chungjungsoo.gptmobile.data.voice.VoiceInputLevel
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceLiveCaptions
import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceTranscriptFrame
import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceWave
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Exercises real rendering with deterministic inputs; this fixture never claims native speech timings. */
@Composable
internal fun VoiceRenderingBenchmarkFixture() {
    var state by remember { mutableStateOf(VoiceState()) }
    var transcript by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var listeningSampled by remember { mutableStateOf(false) }
    val input = remember { MutableStateFlow(VoiceInputLevel()) }
    val output = remember { MutableStateFlow<Float?>(null) }
    val cursor = remember { MutableStateFlow(VoiceCaptionCursor()) }
    LaunchedEffect(state.phase) {
        when (state.phase) {
            VoicePhase.LISTENING -> {
                repeat(120) { tick ->
                    input.value = VoiceInputLevel(abs(sin(tick * .2)).toFloat(), true)
                    if (tick % 10 == 0) state = state.copy(transcript = "Explain the next voice performance measurement ".repeat(tick / 10 + 1))
                    delay(20)
                }
                listeningSampled = true
            }
            VoicePhase.SPEAKING -> {
                repeat(100) { tick ->
                    val reply = "Voice rendering keeps captions readable while the answer grows. ".repeat(tick + 1)
                    state = state.copy(reply = reply)
                    val start = (reply.length - 47).coerceAtLeast(0)
                    cursor.value = VoiceCaptionCursor(start until (start + 9).coerceAtMost(reply.length))
                    output.value = abs(sin(tick * .4)).toFloat()
                    delay(25)
                }
                finished = true
            }
            else -> Unit
        }
    }
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                transcript = false
                finished = false
                listeningSampled = false
                state = VoiceState(phase = VoicePhase.LISTENING)
            }, Modifier.testTag("voice_benchmark_listen")) { Text("Listen") }
            Button({
                transcript = false
                finished = false
                state = state.copy(phase = VoicePhase.SPEAKING)
            }, Modifier.testTag("voice_benchmark_reply")) { Text("Reply") }
            Button({ transcript = true }, Modifier.testTag("voice_benchmark_transcript")) { Text("Transcript") }
        }
        if (state.phase == VoicePhase.LISTENING && state.transcript.isNotEmpty()) Text("Listening input active", Modifier.testTag("voice_benchmark_listening"))
        if (state.phase == VoicePhase.LISTENING && listeningSampled) Text("Input frames sampled", Modifier.testTag("voice_benchmark_listening_sampled"))
        if (finished) Text("Rendering complete", Modifier.testTag("voice_benchmark_complete"))
        Box(Modifier.weight(1f)) {
            if (transcript) {
                VoiceTranscriptFrame("Benchmark profile", true, {}, { transcript = false }, {}, {}) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("voice_benchmark_document")) {
                        Text(state.transcript)
                        Text(state.reply)
                    }
                }
            } else {
                Column {
                    VoiceWave(state, Modifier.fillMaxWidth().height(104.dp), output, input)
                    VoiceLiveCaptions(state, compact = false, modifier = Modifier.fillMaxWidth(), cursor = cursor)
                }
            }
        }
    }
}
