package dev.chungjungsoo.gptmobile.presentation.ui.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.data.research.feature.EvidenceAppraisal
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchWork
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchWorkVersion
import dev.chungjungsoo.gptmobile.data.research.feature.StudyField
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.glassWindowBackdrop

data class ArticleComparison(val work: ResearchWork, val version: ResearchWorkVersion, val fields: List<StudyField>, val appraisals: List<EvidenceAppraisal>)

@Composable
fun ResearchComparisonDialog(articles: List<ArticleComparison>, onClose: () -> Unit) {
    FadingDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize().glassWindowBackdrop(), containerColor = dev.chungjungsoo.gptmobile.presentation.common.overlayWindowContainerColor(), topBar = { OutlinedButton(onClick = onClose) { Text("Back to archive") } }) { padding ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Compare study evidence", style = MaterialTheme.typography.titleLarge)
                    Text("Historical, version-linked comparison. Different outcomes, populations or designs may not be comparable. Shared or unknown study families are not independent studies. No pooled effect or overall truth score is calculated.")
                }
                val fieldKeys = articles.flatMap { it.fields.map { field -> field.name to field.outcomeKey } }.distinct().sortedBy { it.first + it.second }
                articles.forEach { article ->
                    item {
                        Text(article.work.title, style = MaterialTheme.typography.titleMedium)
                        Text("${article.work.publicationType} · ${article.version.coverage.name.lowercase().replace('_', ' ')}")
                        Text("Study family: ${article.work.studyFamilyId ?: "Unknown"} · Peer review: ${article.work.peerReview}")
                        if (article.work.retracted == true) Text("Retraction reported", color = MaterialTheme.colorScheme.error)
                        Text("Independent review: ${article.appraisals.map { it.independentReview }.distinct().joinToString().ifBlank { "Not assessed" }}")
                        Text("Version ${article.version.versionId.take(16)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (fieldKeys.isEmpty()) item { Text("No validated method fields are available. Examine the articles to extract source-linked study information.") }
                fieldKeys.forEach { (name, outcome) ->
                    item {
                        Text("${name.replace('_', ' ')} · $outcome", style = MaterialTheme.typography.titleSmall)
                        articles.forEach { article ->
                            val field = article.fields.firstOrNull { it.name == name && it.outcomeKey == outcome }
                            Text("${article.work.title}: ${field?.value?.let { "$it ${field.unit.orEmpty()}" } ?: field?.missingness?.name?.lowercase()?.replace('_', ' ') ?: "Not assessed"}")
                        }
                    }
                }
            }
        }
    }
}
