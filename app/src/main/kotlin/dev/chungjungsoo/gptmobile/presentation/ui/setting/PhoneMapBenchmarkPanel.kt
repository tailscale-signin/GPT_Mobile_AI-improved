package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.PhoneMapReuseBenchmark
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun PhoneMapBenchmarkPanel(enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var results by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Repository map reuse", style = MaterialTheme.typography.titleMedium)
        Text(
            "Measures the phone map with two fixture files and an isolated memory cache. The GitHub responses are simulated locally.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = {
                val running = job
                if (running != null) {
                    running.cancel()
                } else {
                    results = ""
                    job = scope.launch {
                        try {
                            results = PhoneMapReuseBenchmark.run().joinToString("\n\n") { row ->
                                val p95 = String.format(Locale.getDefault(), "%.2f", row.p95Nanos / 1_000_000.0)
                                "${row.scenario} · ${row.passed}/${row.trials} passed · p95 $p95 ms\n" +
                                    "Max reads/trial · commits ${row.commitReads} · trees ${row.treeReads} · blobs ${row.blobReads}\n" +
                                    "Max retained cache charge ${(row.maxCacheChargeBytes + 1023) / 1024} KiB"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            results = "The benchmark stopped. ${failure.message.orEmpty()}"
                        } finally {
                            job = null
                        }
                    }
                }
            },
            enabled = enabled || job != null
        ) {
            Text(if (job == null) "Run ${PhoneMapReuseBenchmark.TRIALS} trials" else "Cancel map benchmark")
        }
        if (results.isNotBlank()) Text(results, style = MaterialTheme.typography.bodySmall)
    }
}
