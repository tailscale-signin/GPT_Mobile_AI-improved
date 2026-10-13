package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ClaimSupportClass
import dev.chungjungsoo.gptmobile.data.agent.ContributionLineage
import dev.chungjungsoo.gptmobile.data.agent.ValidatedCombinedProvenance
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.URI
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class InspectedSentence(
    val start: Int,
    val end: Int,
    val text: String,
    val supportClass: ClaimSupportClass,
    val profileUids: Set<String>,
    val spanIds: Set<String>,
    val compared: Long,
    val expected: Long,
    val warning: String?
)

/** The inspector never creates support labels from lexical resemblance or a model's confidence. */
internal fun contributionSentenceInventory(answer: String, lineage: ContributionLineage, validated: ValidatedCombinedProvenance?, policyRevision: String): List<InspectedSentence> {
    val current = validated?.takeIf {
        it.answerDigest == researchHash(answer) &&
            it.corpusDigest == lineage.corpusDigest &&
            it.parserVersion == ATTRIBUTION_MARKDOWN_PARSER_VERSION &&
            it.policyRevision == policyRevision &&
            it.warning == null
    }
    val boundaries = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(answer) }
    val ranges = current?.sentences.orEmpty().map { it.startUtf16 to it.endUtf16 }
    // Include unclassified text as UNKNOWN; an absent sidecar never means unsupported or exclusive.
    val starts = sortedSetOf(0, answer.length)
    var boundary = boundaries.first()
    while (boundary != BreakIterator.DONE) {
        starts += boundary
        boundary = boundaries.next()
    }
    ranges.forEach { (start, end) ->
        if (start >= 0 && end > start && end <= answer.length) {
            starts += start
            starts += end
        }
    }
    return starts.zipWithNext().mapNotNull { (start, end) ->
        val text = answer.substring(start, end)
        if (text.isBlank()) return@mapNotNull null
        val match = current?.sentences?.singleOrNull { it.startUtf16 <= start && it.endUtf16 >= end }
        InspectedSentence(start, end, text, match?.supportClass ?: ClaimSupportClass.UNKNOWN, match?.supporterUids.orEmpty(), match?.supportSpanIds.orEmpty(), match?.comparedSpans ?: 0, match?.expectedSpans ?: lineage.spans.size.toLong(), match?.warning ?: current?.warning)
    }
}

internal fun contributionEvidenceUrl(value: String): String? = runCatching {
    val uri = URI(value)
    value.takeIf { uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CombinedContributionInspector(
    answer: String,
    sources: List<CombinedModelResponse>,
    selectedProfileUids: List<String>,
    identities: Map<String, ProfileColorIdentity>,
    primaryUid: String?,
    onOpenContribution: (String) -> Unit,
    validated: ValidatedCombinedProvenance? = null,
    policyRevision: String = ""
) {
    var open by remember { mutableStateOf(false) }
    var openedAnswer by remember { mutableStateOf("") }
    TextButton(onClick = {
        openedAnswer = answer
        open = true
    }, enabled = sources.isNotEmpty()) { Text(stringResource(R.string.combined_inspect_support)) }
    if (!open) return
    val data by produceState<Pair<ContributionLineage, List<InspectedSentence>>?>(null, openedAnswer, sources, selectedProfileUids, validated, policyRevision) {
        value = withContext(Dispatchers.Default) {
            val lineage = ContributionLineage.freeze(selectedProfileUids, sources)
            lineage to contributionSentenceInventory(openedAnswer, lineage, validated, policyRevision)
        }
    }
    var selectedRange by remember(openedAnswer, sources) { mutableStateOf<Pair<Int, Int>?>(null) }
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        val snapshot = data
        if (snapshot == null) {
            Text(stringResource(R.string.combined_inspection_loading), Modifier.padding(20.dp))
        } else {
            val (lineage, sentences) = snapshot
            val selected = sentences.firstOrNull { it.start to it.end == selectedRange }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.combined_inspect_support), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.combined_inspection_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (selected == null) {
                    items(lineage.revisions, key = { "profile:${it.profileUid}" }) { revision ->
                        val source = sources.singleOrNull { it.platformUid == revision.profileUid }
                        val identity = identities[revision.profileUid]
                        Column {
                            Text(
                                listOfNotNull(identity?.let { "[${it.marker}]" }, source?.platformName ?: revision.profileUid, stringResource(R.string.combined_inspection_primary).takeIf { revision.profileUid == primaryUid }).joinToString(" "),
                                color = identity?.let { ProfileAttributionPalette.foreground(it, MaterialTheme.colorScheme.surfaceContainerLow) } ?: MaterialTheme.colorScheme.onSurface
                            )
                            Text(revision.availability.name, style = MaterialTheme.typography.labelSmall)
                            TextButton(enabled = source != null, onClick = {
                                open = false
                                onOpenContribution(revision.profileUid)
                            }) { Text(stringResource(R.string.combined_open_contribution)) }
                        }
                    }
                    items(sentences, key = { "sentence:${it.start}:${it.end}" }) { sentence ->
                        TextButton(onClick = { selectedRange = sentence.start to sentence.end }, modifier = Modifier.fillMaxWidth()) {
                            Text(sentence.text, maxLines = 3, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (sentences.isEmpty()) item { Text(stringResource(R.string.combined_inspection_no_answer)) }
                } else {
                    item {
                        TextButton(onClick = { selectedRange = null }) { Text(stringResource(R.string.combined_inspection_back)) }
                        SelectionContainer { Text(selected.text) }
                        Text(stringResource(R.string.combined_support_class, selected.supportClass.name), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.combined_comparison_coverage, selected.compared, selected.expected))
                        if (selected.supportClass == ClaimSupportClass.UNKNOWN) Text(stringResource(R.string.combined_support_unknown))
                        selected.warning?.let { Text(it) }
                    }
                    items(selected.spanIds.toList(), key = { it }) { id ->
                        val span = lineage.spans.singleOrNull { it.id == id }
                        val source = sources.singleOrNull { it.platformUid == span?.profileUid }
                        val excerpt = lineage.resolve(id, sources, allowLocalOnly = true)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(source?.platformName ?: stringResource(R.string.combined_evidence_unavailable), style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text(excerpt ?: stringResource(R.string.combined_evidence_unavailable)) }
                            span?.let { Text(stringResource(R.string.combined_contribution_revision, it.bodyDigest), style = MaterialTheme.typography.labelSmall) }
                            Row {
                                TextButton(enabled = source != null && excerpt != null, onClick = {
                                    source?.let {
                                        open = false
                                        onOpenContribution(it.platformUid)
                                    }
                                }) { Text(stringResource(R.string.combined_open_contribution)) }
                            }
                            source?.evidenceRefs.orEmpty().mapNotNull(::contributionEvidenceUrl).distinct().forEach { url ->
                                TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(stringResource(R.string.combined_open_evidence, url)) }
                            }
                        }
                    }
                    if (selected.spanIds.isEmpty()) item { Text(stringResource(R.string.combined_no_verified_support)) }
                }
            }
        }
    }
}
