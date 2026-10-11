package dev.chungjungsoo.gptmobile.presentation.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-window adapter for existing validated draft forms. Callers retain all field and save logic. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AppFormDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    dirty: Boolean = true
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val cancel = { if (dirty) discard = true else onDismissRequest() }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BackHandler(onBack = cancel)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = cancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Box(Modifier.weight(1f)) { title?.invoke() }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) { text?.invoke() }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = cancel) { Text("Cancel") }
                        confirmButton()
                    }
                }
            }
        }
        if (discard) {
            AlertDialog(
                onDismissRequest = { discard = false },
                title = { Text("Discard this draft?") },
                text = { Text("Changes in this editor have not been saved.") },
                confirmButton = { TextButton(onClick = onDismissRequest) { Text("Discard") } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } }
            )
        }
    }
}
