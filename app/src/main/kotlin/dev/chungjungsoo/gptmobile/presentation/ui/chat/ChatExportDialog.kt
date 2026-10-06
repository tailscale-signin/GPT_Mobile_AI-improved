package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun ChatExportDialog(onDismiss: () -> Unit, onExport: (ChatExportFormat) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FileDownload, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Export conversation answers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Choose how to save the selected AI responses.", style = MaterialTheme.typography.bodyMedium)
                ExportFormatCard(
                    "Plain text",
                    ".txt",
                    "Easy to read in any text editor",
                    Icons.Rounded.TextSnippet,
                    "Saves readable text in a .txt file. Markdown formatting is removed, source links remain, and code stays readable. Exports the selected response revisions without prompts, thinking, or tool activity."
                ) { onExport(ChatExportFormat.PLAIN_TEXT) }
                ExportFormatCard(
                    "Markdown",
                    ".md",
                    "Keep headings, links, tables and code",
                    Icons.Rounded.Description,
                    "Saves the original response formatting in a .md file. Open it in a Markdown editor to render headings, tables, source links, and code blocks. Exports the selected response revisions without prompts, thinking, or tool activity."
                ) { onExport(ChatExportFormat.MARKDOWN) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportFormatCard(title: String, extension: String, subtitle: String, icon: ImageVector, help: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$title · $extension", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsHelpIcon(help)
        }
    }
}
