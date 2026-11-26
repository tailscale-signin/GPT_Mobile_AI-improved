package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import java.util.Locale

@Composable
internal fun RecognitionLanguageChoice(language: String, backend: VoiceRecognitionBackend, select: (String, VoiceRecognitionBackend) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Recognition language · ${Locale.forLanguageTag(language).displayName}")
    }
    if (choosing) {
        var search by remember { mutableStateOf("") }
        val options = remember(language) {
            (
                listOf(language, Locale.getDefault().toLanguageTag()) + Locale.getISOCountries().map { "en-$it" } +
                    Locale.getAvailableLocales().filter { it.language.isNotEmpty() && it.country.isNotEmpty() && it.variant.isEmpty() }
                        .map { it.toLanguageTag() }
                ).distinct().map { it to Locale.forLanguageTag(it).displayName }.sortedBy { it.second }
        }
        val matches = remember(options, search) { options.filter { (tag, name) -> name.contains(search, true) || tag.contains(search, true) } }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Recognition language") },
            text = {
                Column {
                    OutlinedTextField(search, { search = it }, label = { Text("Find a language or region") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(matches, key = { it.first }) { (tag, name) ->
                            TextButton(onClick = {
                                val englishBackend = if (SpeechLanguages.recognitionBackend(language) == VoiceRecognitionBackend.LOCAL_MOONSHINE) backend else VoiceRecognitionBackend.LOCAL_MOONSHINE
                                select(tag, SpeechLanguages.recognitionBackend(tag, englishBackend))
                                choosing = false
                            }) { Text("$name · $tag") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } }
        )
    }
    if (SpeechLanguages.recognitionBackend(language) == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
        VoiceChoice(
            "Recognition engine",
            backend.name,
            listOf(
                VoiceRecognitionBackend.LOCAL_MOONSHINE.name to "Moonshine",
                VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE.name to "Android offline recognizer"
            ),
            { select(language, VoiceRecognitionBackend.valueOf(it)) }
        )
    }
    if (backend == VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE) {
        Text("Android offline recognition requires a language pack for the selected language and region on this phone.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SynthesisLanguageChoices(value: VoiceSynthesisPreferences, kokoroVoices: Boolean, change: (VoiceSynthesisPreferences) -> Unit) {
    VoiceChoice("Reply speech language", value.profile.tag, SpeechLanguages.synthesis.map { it.tag to it.label }, { change(value.selectLanguage(it)) })
    if (kokoroVoices) {
        VoiceChoice("Voice for ${value.profile.label}", value.voiceId, value.profile.voices.map { it to it.substringAfter('_').replaceFirstChar(Char::uppercaseChar) }, { change(value.selectVoice(it)) })
    }
}
