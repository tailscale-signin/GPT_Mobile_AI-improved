package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationDictionary
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationOverrides
import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoicePronunciationEditor(language: String, value: Map<String, String>, inherited: Map<String, String>, save: (Map<String, String>) -> Unit) {
    var editor by remember(language, value) { mutableStateOf(VoicePronunciationTransfer.editor(value)) }
    var word by remember(language) { mutableStateOf("") }
    var spelling by remember(language) { mutableStateOf("") }
    var preview by remember(language) { mutableStateOf("") }
    var status by remember(language) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var importLanguage by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val currentLanguage by rememberUpdatedState(language)
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    val parsed = remember(editor) { parsePronunciationEditor(editor) }
    val imported = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requested = importLanguage
        importLanguage = null
        if (uri != null && requested == null) status = "The import was interrupted. Select the dictionary and import the file again."
        if (uri != null && requested != null) {
            busy = true
            scope.launch {
                try {
                    val entries = withContext(Dispatchers.IO) {
                        requireNotNull(resolver.openInputStream(uri)).use {
                            VoicePronunciationTransfer.decode(VoicePronunciationTransfer.read(it), requested)
                        }
                    }
                    require(currentLanguage == requested) { "The language changed. Import again into the intended dictionary." }
                    val current = requireNotNull(parsePronunciationEditor(editor)) { "Correct the editor entries before importing." }
                    editor = VoicePronunciationTransfer.editor(VoicePronunciationTransfer.merge(current, entries))
                    status = "${entries.size} imported entries are ready to review. Save pronunciations to apply them."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalArgumentException) {
                    status = error.message ?: "The pronunciation file could not be imported."
                } catch (_: Exception) {
                    status = "The pronunciation file could not be read. Choose a readable UTF-8 pronunciation file."
                } finally {
                    busy = false
                }
            }
        }
    }
    val exported = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = exportText
        exportText = null
        if (uri != null && text == null) status = "The export was interrupted before writing. Export the dictionary again."
        if (uri != null && text != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                    status = "Pronunciation dictionary exported."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "The dictionary could not be exported. Your saved entries are still available."
                } finally {
                    busy = false
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (inherited.isNotEmpty()) Text("${inherited.size} shared corrections also apply.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(word, { if (it.length <= 48) word = it }, Modifier.fillMaxWidth().testTag("voice_pronunciation_word"), label = { Text("Word or phrase to correct") }, singleLine = true)
        OutlinedTextField(spelling, { if (it.length <= 96) spelling = it }, Modifier.fillMaxWidth().testTag("voice_pronunciation_spelling"), label = { Text("How it should sound") }, singleLine = true)
        TextButton(
            onClick = {
                val current = parsePronunciationEditor(editor)
                if (current != null && VoicePronunciationOverrides.valid(word.trim(), spelling.trim())) {
                    try {
                        editor = VoicePronunciationTransfer.editor(VoicePronunciationTransfer.merge(current, mapOf(word.trim() to spelling.trim())))
                        preview = word.trim()
                        word = ""
                        spelling = ""
                        status = "Correction added to the editor. Save pronunciations to apply it."
                    } catch (error: IllegalArgumentException) {
                        status = error.message
                    }
                }
            },
            enabled = !busy &&
                parsed != null &&
                VoicePronunciationOverrides.valid(word.trim(), spelling.trim()) &&
                (parsed.size < VoicePronunciationOverrides.MAX_ENTRIES || parsed.keys.any { VoicePronunciationOverrides.key(it) == VoicePronunciationOverrides.key(word.trim()) })
        ) { Text("Add or replace correction") }
        OutlinedTextField(
            value = editor,
            onValueChange = { if (it.length <= VoicePronunciationOverrides.MAX_EDITOR_CHARS) editor = it },
            modifier = Modifier.fillMaxWidth().testTag("voice_pronunciation_editor"),
            label = { Text("Word or term = spoken pronunciation") },
            supportingText = { Text("One term = spoken spelling per line · $language · ${parsed?.size ?: "?"}/${VoicePronunciationOverrides.MAX_ENTRIES} entries. Remove a line to delete a correction.") },
            minLines = 2,
            maxLines = 6,
            enabled = !busy,
            isError = parsed == null
        )
        Button(onClick = {
            parsed?.let(save)
            status = "Pronunciations applied to the next reply."
        }, enabled = !busy && parsed != null && parsed != value) { Text("Save pronunciations") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                importLanguage = language
                imported.launch(arrayOf("text/plain", "application/octet-stream"))
            }, modifier = Modifier.weight(1f), enabled = !busy && parsed != null) { Text("Import dictionary") }
            TextButton(onClick = {
                parsePronunciationEditor(editor)?.let {
                    exportText = VoicePronunciationTransfer.encode(language, it)
                    exported.launch("pronunciations-$language.txt")
                }
            }, modifier = Modifier.weight(1f), enabled = !busy && parsed != null) { Text("Export dictionary") }
        }
        OutlinedTextField(preview, { if (it.length <= 512) preview = it }, Modifier.fillMaxWidth().testTag("voice_pronunciation_preview"), label = { Text("Preview spoken text") }, maxLines = 3)
        if (preview.isNotBlank() && parsed != null) {
            val projected = remember(preview, inherited, parsed, language) {
                val retained = inherited.filterKeys { key -> parsed.keys.none { VoicePronunciationOverrides.key(it) == VoicePronunciationOverrides.key(key) } }
                VoicePronunciationDictionary(retained + parsed, language).project(preview, englishFallback = true).text
            }
            Text("Spoken text: $projected", style = MaterialTheme.typography.bodySmall)
            Text("Captions keep the original wording. The voice model determines the final pronunciation.", style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
