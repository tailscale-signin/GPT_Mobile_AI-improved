package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguage
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import java.util.Locale

@Composable
internal fun RecognitionLanguageChoice(language: String, backend: VoiceRecognitionBackend, model: VoiceRecognitionModel = VoiceRecognitionModel.MOONSHINE_TINY, select: (String, VoiceRecognitionBackend) -> Unit) {
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
                                val selectedBackend = if (model.supportsLanguage(language)) backend else VoiceRecognitionBackend.LOCAL_MOONSHINE
                                select(tag, SpeechLanguages.recognitionBackend(tag, selectedBackend, model))
                                choosing = false
                            }) { Text("$name · $tag") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } }
        )
    }
    if (model.supportsLanguage(language)) {
        VoiceChoice(
            "Recognition engine",
            backend.name,
            listOf(
                VoiceRecognitionBackend.LOCAL_MOONSHINE.name to model.title,
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
internal fun SynthesisLanguageChoices(value: VoiceSynthesisPreferences, kokoroVoices: Boolean, enabled: Boolean = true, change: (VoiceSynthesisPreferences) -> Unit) {
    VoiceChoice("Reply speech language", value.profile.tag, SpeechLanguages.synthesis.map { it.tag to it.label }, { change(value.selectLanguage(it)) }, enabled)
    if (kokoroVoices) {
        VoiceChoice("Voice for ${value.profile.label}", value.voiceId, value.profile.voices.map { it to it.substringAfter('_').replaceFirstChar(Char::uppercaseChar) }, { change(value.selectVoice(it)) }, enabled)
    }
}

@Composable
internal fun VoiceReplyLanguageDialog(
    selected: String,
    translationMode: Boolean,
    changeTranslationMode: (Boolean) -> Unit,
    select: (String) -> Unit,
    dismiss: () -> Unit,
    profiles: List<SpeechLanguage> = SpeechLanguages.synthesis,
    translationPair: VoiceTranslationPair = VoiceTranslationPair(),
    changeTranslationPair: (VoiceTranslationPair) -> Unit = {},
    translationModel: LocalVoiceModelState? = null,
    installTranslationModel: () -> Unit = {},
    cancelTranslationModel: () -> Unit = {},
    translationSetupNotice: String? = null
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (translationMode) "Translation languages" else "Reply language", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Row(
                    Modifier.toggleable(translationMode, role = Role.Checkbox, onValueChange = changeTranslationMode)
                        .testTag("voice_translation_mode").padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Translate only", style = MaterialTheme.typography.labelMedium)
                    Checkbox(translationMode, onCheckedChange = null)
                }
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp).testTag("voice_reply_language_list")) {
                item {
                    translationSetupNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    Text(
                        if (translationMode) {
                            "Speak either language to translate into the other, using that language’s voice. Questions stay questions and commands stay commands; the app translates them without answering or carrying them out."
                        } else {
                            "Answer in the selected language and use its voice for playback."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (translationMode) {
                    val pair = translationPair.normalized()
                    val choices = MediaPipeSpeechVoices.profiles(MediaPipeSpeechEngine.POCKET_MULTILINGUAL)
                    val base = choices.first { it.tag == pair.baseLanguage }
                    val target = choices.first { it.tag == pair.translationLanguage }
                    item {
                        Column(Modifier.testTag("voice_translation_base_language")) {
                            VoiceChoice("Base language", base.tag, choices.filter { it.tag != target.tag }.map { it.tag to it.label }, { tag ->
                                changeTranslationPair(pair.copy(baseLanguage = tag, sourceLanguage = if (pair.sourceLanguage == base.tag) tag else pair.sourceLanguage).normalized())
                            })
                        }
                    }
                    item {
                        Column(Modifier.testTag("voice_translation_base_voice").padding(bottom = 12.dp)) {
                            VoiceChoice("Voice for ${base.label}", pair.baseVoiceId, base.voices.map { it to it.replaceFirstChar(Char::uppercaseChar) }, {
                                changeTranslationPair(pair.copy(baseVoiceId = it).normalized())
                            })
                        }
                    }
                    item {
                        Column(Modifier.testTag("voice_translation_target_language")) {
                            VoiceChoice("Translation language", target.tag, choices.filter { it.tag != base.tag }.map { it.tag to it.label }, { tag ->
                                changeTranslationPair(pair.copy(translationLanguage = tag, sourceLanguage = if (pair.sourceLanguage == target.tag) tag else pair.sourceLanguage).normalized())
                            })
                        }
                    }
                    item {
                        Column(Modifier.testTag("voice_translation_target_voice").padding(bottom = 12.dp)) {
                            VoiceChoice("Voice for ${target.label}", pair.translationVoiceId, target.voices.map { it to it.replaceFirstChar(Char::uppercaseChar) }, {
                                changeTranslationPair(pair.copy(translationVoiceId = it).normalized())
                            })
                        }
                    }
                    item {
                        Column(Modifier.testTag("voice_translation_source_language").padding(bottom = 12.dp)) {
                            VoiceChoice("Spoken language", pair.sourceLanguage.orEmpty(), listOf("" to "Auto · either language", base.tag to base.label, target.tag to target.label), {
                                changeTranslationPair(pair.copy(sourceLanguage = it.takeIf(String::isNotEmpty)).normalized())
                            })
                            Text("Auto needs compatible Android offline recognition for both languages. Choosing a source language lets you use your selected local model when it supports that language.", style = MaterialTheme.typography.bodySmall)
                            Text("If detection is unavailable or the words could belong to either language, choose the language you are speaking, then retry the saved text.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        TranslationVoicePack(translationModel, installTranslationModel, cancelTranslationModel)
                    }
                } else {
                    items(profiles, key = { it.tag }) { language ->
                        Row(
                            Modifier.fillMaxWidth().selectable(language.tag == selected, role = Role.RadioButton, onClick = { select(language.tag) })
                                .testTag("voice_reply_language_${language.tag}").padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(selected = language.tag == selected, onClick = null)
                            Text(language.label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}

@Composable
private fun TranslationVoicePack(state: LocalVoiceModelState?, install: () -> Unit, cancel: () -> Unit) {
    val model = MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.POCKET_MULTILINGUAL)
    val current = state ?: LocalVoiceModelState(totalBytes = model.downloadBytes)
    Column(Modifier.testTag("voice_translation_pack"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Pocket Multilingual · Offline voices", style = MaterialTheme.typography.titleSmall)
        when (current.status) {
            LocalVoiceModelStatus.READY -> Text("Installed and verified", color = MaterialTheme.colorScheme.primary)
            LocalVoiceModelStatus.CHECKING -> Text("Checking the multilingual voice pack…")
            LocalVoiceModelStatus.DOWNLOADING -> {
                VoiceTransferProgress(current.transferStage, current.downloadedBytes, current.totalBytes)
                TextButton(onClick = cancel, modifier = Modifier.testTag("voice_translation_pack_cancel")) { Text("Cancel download") }
            }
            else -> {
                Text("Both translation voices use this pack. The English-only pack does not provide both languages.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = install, modifier = Modifier.testTag("voice_translation_pack_install")) {
                    Text("Download multilingual voices · ${(model.downloadBytes + 524_288) / 1_048_576} MiB")
                }
            }
        }
        current.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
