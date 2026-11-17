package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubRichSummaryPresentation
import dev.chungjungsoo.gptmobile.data.github.GitHubSummaryWorkbook
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Source-count chart; rendering never infers a pass or a time series. */
@Composable
internal fun GitHubRichSummaryChart(markdown: String) {
    val csv = remember(markdown) { GitHubRichSummaryPresentation.csv(markdown) } ?: return
    val rows = remember(markdown) { GitHubRichSummaryPresentation.chartData(markdown) }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = when {
                    markdown.startsWith("### Partial recorded check results") && rows.isNotEmpty() -> "Partial check distribution"
                    markdown.startsWith("### Partial recorded check results") -> "Partial recorded check table"
                    rows.isNotEmpty() -> "Recorded check distribution"
                    else -> "Recorded check table"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(csv)) }, modifier = Modifier.testTag("github_summary_copy_csv")) {
                Text("Copy CSV")
            }
            SaveSummaryWorkbook(markdown)
        }
        if (rows.isNotEmpty()) {
            val maximum = rows.maxOf { it.count }.coerceAtLeast(1)
            Column(
                Modifier.fillMaxWidth().testTag("github_check_count_chart"),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                rows.forEach { row ->
                    val barColor: Color = when (row.label) {
                        "Failed" -> colors.error
                        "Skipped" -> colors.tertiary
                        "In progress" -> colors.secondary
                        else -> colors.primary
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                            contentDescription = "${row.label}: ${row.count} recorded checks"
                        },
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            Text(row.count.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                        Box(
                            Modifier.fillMaxWidth().height(8.dp).background(colors.onSurface.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
                        ) {
                            if (row.count > 0) {
                                Box(
                                    Modifier.fillMaxWidth(row.count.toFloat() / maximum).height(8.dp)
                                        .background(barColor, RoundedCornerShape(6.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveSummaryWorkbook(markdown: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GitHubSummaryWorkbook.MIME_TYPE)) { uri ->
        val table = pending?.let(GitHubRichSummaryPresentation::checkTable)
        pending = null
        if (uri != null && table != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Document unavailable")
                        GitHubSummaryWorkbook.write(output, table)
                        true
                    } catch (_: IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                }
                val message = if (saved) "Workbook saved" else "Could not save the workbook"
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    TextButton(
        enabled = pending == null,
        onClick = {
            pending = markdown
            try {
                launcher.launch("github-recorded-checks.xlsx")
            } catch (_: android.content.ActivityNotFoundException) {
                pending = null
                android.widget.Toast.makeText(context, "No document picker available", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.testTag("github_summary_save_xlsx")
    ) {
        Text("Save XLSX")
    }
}
