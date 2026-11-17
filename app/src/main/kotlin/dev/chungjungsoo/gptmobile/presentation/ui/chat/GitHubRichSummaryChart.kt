package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubRichSummaryPresentation

/** Source-count chart; rendering never infers a pass or a time series. */
@Composable
internal fun GitHubRichSummaryChart(markdown: String) {
    val csv = remember(markdown) { GitHubRichSummaryPresentation.csv(markdown) } ?: return
    val rows = remember(markdown) { GitHubRichSummaryPresentation.chartData(markdown) }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = when {
                    markdown.startsWith("### Partial recorded check results") && rows.isNotEmpty() -> "Partial check distribution"
                    markdown.startsWith("### Partial recorded check results") -> "Partial recorded check table"
                    rows.isNotEmpty() -> "Recorded check distribution"
                    else -> "Recorded check table"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(csv)) }, modifier = Modifier.testTag("github_summary_copy_csv")) {
                Text("Copy CSV")
            }
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
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                            contentDescription = "${row.label}: ${row.count} recorded checks"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(row.label, Modifier.width(95.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(
                            Modifier.weight(1f).height(10.dp).background(colors.onSurface.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
                        ) {
                            if (row.count > 0) {
                                Box(
                                    Modifier.fillMaxWidth(row.count.toFloat() / maximum).height(10.dp)
                                        .background(barColor, RoundedCornerShape(6.dp))
                                )
                            }
                        }
                        Text(row.count.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
