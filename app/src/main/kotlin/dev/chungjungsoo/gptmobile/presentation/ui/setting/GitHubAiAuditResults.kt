package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog

@Composable
internal fun GitHubAiAuditResults(batch: BenchmarkBatch?, runs: List<BenchmarkRun>, onDismiss: () -> Unit) {
    val results = remember(batch, runs) { batch?.let { benchmarkBatchResults(it, runs) }.orEmpty() }
    FadingAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.github_ai_audit_saved_result)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (batch == null) {
                    Text(stringResource(R.string.github_ai_audit_result_missing))
                } else {
                    Text(benchmarkResultDate(batch.createdAt), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.github_ai_audit_result_scope), style = MaterialTheme.typography.bodySmall)
                    results.forEach { BenchmarkResultCard(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } }
    )
}
