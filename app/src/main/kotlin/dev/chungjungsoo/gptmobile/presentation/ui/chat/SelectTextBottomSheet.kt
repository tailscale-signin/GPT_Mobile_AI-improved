package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppEditorScaffold

/** Readable full-screen selection surface; the exact supplied response is retained. */
@Composable
fun SelectTextBottomSheet(text: String, onDismissRequest: () -> Unit, modifier: Modifier = Modifier) {
    FadingDialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        AppEditorScaffold("Select text", false, onDismissRequest, onDismissRequest, saveLabel = "Done") { padding ->
            SelectionContainer(modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
