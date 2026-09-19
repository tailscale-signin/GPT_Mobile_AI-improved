package com.example.gptmobileai.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.debug.ExportService

@Composable
fun ExportDialog(
    exportService: ExportService,
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf("json") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Format") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedFormat == "json",
                        onClick = { selectedFormat = "json" }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("JSON - Human-readable format")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedFormat == "csv",
                        onClick = { selectedFormat = "csv" }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CSV - Spreadsheet format")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    exportService.shareExport(selectedFormat)
                    onDismiss()
                }
            ) {
                Text("Share Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
