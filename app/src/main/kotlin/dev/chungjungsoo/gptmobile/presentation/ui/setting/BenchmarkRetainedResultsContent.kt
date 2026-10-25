package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkComparisonRow
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRetest
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType

/** Retained evidence can be inspected independently of the current profile roster. */
internal fun LazyListScope.benchmarkResultsContent(
    mode: BenchmarkMode,
    enabled: Boolean,
    profiles: List<PlatformV2>,
    results: List<BenchmarkResultPresentation>,
    scoreRows: List<BenchmarkScoreRow>,
    retests: List<BenchmarkRetest>,
    backendComparisons: List<BenchmarkComparisonRow>,
    onMode: (BenchmarkMode) -> Unit,
    onRun: () -> Unit
) {
    val hasLocalEvidence = profiles.any { it.compatibleType == ClientType.LITERT_LM } ||
        results.any { it.latestRun?.local == true || it.latestQueueItem?.profile?.compatibleType == ClientType.LITERT_LM }
    item { Text("Current results · request policies remain separate", style = MaterialTheme.typography.titleMedium) }
    items(results.filter { it.mode == mode }, key = { it.identity }) { result -> BenchmarkResultCard(result) }
    item { BenchmarkPlanPicker(mode, enabled, includeDiagnostics = true, includeCpuExperiment = hasLocalEvidence, includePerformance = hasLocalEvidence, onSelect = onMode) }
    item {
        BenchmarkSideBySide(scoreRows.filter { it.cohort.startsWith(mode.name + "|") }.map { row -> retests.firstOrNull { it.current.resultId == row.resultId }?.current ?: row }, profiles, retests)
    }
    item { BenchmarkBackendComparison(backendComparisons) }
    item { BenchmarkAction(stringResource(R.string.v0950_text_08d721ca13), Icons.Rounded.PlayArrow, onClick = onRun, modifier = Modifier.fillMaxWidth()) }
}

internal fun benchmarkHistoryForSelection(history: List<BenchmarkRun>, selectedUid: String?): List<BenchmarkRun> =
    if (selectedUid == null) history else history.filter { it.profileUid == selectedUid }
