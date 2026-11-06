package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
internal fun ChatExportDialog(
    onDismiss: () -> Unit,
    debugMode: Boolean = false,
    includeUserMessages: Boolean = false,
    diagnosticNotice: String? = null,
    onReducedDebugExport: (() -> Unit)? = null,
    onResumeDebugExport: (() -> Unit)? = null,
    onExport: (ChatExportFormat) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FileDownload, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Export conversation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (includeUserMessages) "Save your messages and the selected AI replies. Active and failed responses are included as a snapshot." else "Choose how to save this conversation.", style = MaterialTheme.typography.bodyMedium)
                ExportFormatCard(
                    "Plain text",
                    ".txt",
                    "Easy to read in any text editor",
                    Icons.Rounded.TextSnippet,
                    if (includeUserMessages) "Includes your messages and selected AI replies in order, with repository and snapshot details. System prompts, thinking and tool activity are excluded." else "Saves readable text in a .txt file. Markdown formatting is removed, source links remain, and code stays readable. Exports the selected response revisions without prompts, thinking, or tool activity."
                ) { onExport(ChatExportFormat.PLAIN_TEXT) }
                ExportFormatCard(
                    "Markdown",
                    ".md",
                    "Keep headings, links, tables and code",
                    Icons.Rounded.Description,
                    if (includeUserMessages) "Includes your messages and selected AI replies with headings, tables, links and code. System prompts, thinking and tool activity are excluded." else "Saves the original response formatting in a .md file. Open it in a Markdown editor to render headings, tables, source links, and code blocks. Exports the selected response revisions without prompts, thinking, or tool activity."
                ) { onExport(ChatExportFormat.MARKDOWN) }
                if (debugMode) {
                    diagnosticNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ExportFormatCard(
                        "Debug export",
                        ".debug.zip",
                        "Conversation records and diagnostic evidence",
                        Icons.Rounded.Description,
                        "Captures stored conversation, tool, research, profile and log records in independent sections. The manifest reports exact record counts, capture times, memory measurements and any omissions. Oversized records and unavailable sources are reported explicitly. Credentials are redacted; the file can contain private conversation data."
                    ) { onExport(ChatExportFormat.DEBUG) }
                    onReducedDebugExport?.let { reduced ->
                        ExportFormatCard(
                            "Low-memory diagnostics",
                            ".zip",
                            "Preserve critical logs with smaller capture limits",
                            Icons.Rounded.Description,
                            "Uses smaller record and section limits. Every omitted record or section is listed in the manifest. This is useful when a full capture cannot finish."
                        ) { reduced() }
                    }
                    onResumeDebugExport?.let { resume ->
                        ExportFormatCard(
                            "Resume diagnostic capture",
                            ".zip",
                            "Keep verified sections and retry unfinished sections",
                            Icons.Rounded.Description,
                            "Reuses intact completed sections from the last unfinished capture and retries missing sections. Each section keeps its own capture timestamp because the conversation may have changed."
                        ) { resume() }
                    }
                }
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
