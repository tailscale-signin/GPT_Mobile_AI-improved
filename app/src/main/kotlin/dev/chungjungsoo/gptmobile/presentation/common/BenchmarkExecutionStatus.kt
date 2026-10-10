package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionCoordinator
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkProgress
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class BenchmarkExecutionStatusViewModel @Inject constructor(
    coordinator: BenchmarkExecutionCoordinator
) : ViewModel() {
    val progress: StateFlow<BenchmarkProgress?> = coordinator.progressMutable
    fun cancel() = coordinator.cancel()
}

@Composable
fun BenchmarkExecutionBanner(
    modifier: Modifier = Modifier,
    viewModel: BenchmarkExecutionStatusViewModel = hiltViewModel()
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val current = progress ?: return
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Benchmark running · ${current.profileName} · ${current.testName} (${current.completed + 1}/${current.total})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = viewModel::cancel) { Text("Stop") }
        }
    }
}
