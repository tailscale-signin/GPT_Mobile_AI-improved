package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyBenchmark
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyBenchmarkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PrivacyBenchmarkPanel() {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<PrivacyBenchmarkResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("privacy_benchmark_toggle")) {
            Text(if (expanded) "Hide privacy check" else "Check privacy on this device")
        }
        if (expanded) {
            Text("Run fictional examples locally to check detected details, preserved requests, restoration and timing. Your chats, saved private terms and cloud profiles are not used.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(
                    enabled = running == null,
                    modifier = Modifier.testTag("privacy_benchmark_run"),
                    onClick = {
                        result = null
                        status = "Checking…"
                        running = scope.launch {
                            try {
                                result = withContext(Dispatchers.Default) { PrivacyBenchmark.run() }
                                status = null
                            } catch (cancelled: CancellationException) {
                                status = "Check cancelled."
                                throw cancelled
                            } catch (_: Exception) {
                                status = "The local check could not finish. Try again."
                            } finally {
                                running = null
                            }
                        }
                    }
                ) { Text("Run local check") }
                if (running != null) TextButton(onClick = { running?.cancel() }) { Text("Cancel") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            result?.let { value ->
                Text(if (value.passed) "All ${value.cases} fictional examples passed" else "Some fictional examples need attention", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("privacy_benchmark_result"))
                Text("Private values protected: ${value.protectedValues}/${value.expectedPrivateValues}. Public phrases preserved: ${value.preservedPhrases}/${value.expectedPublicPhrases}. Exact restorations: ${value.exactRestorations}/${value.cases}. Separate request maps: ${if (value.isolatedMaps) "passed" else "failed"}.", style = MaterialTheme.typography.bodySmall)
                Text("Local CPU timing (${value.timingSamples} samples): masking and encryption median ${value.maskingMedianMicros} μs, p95 ${value.maskingP95Micros} μs; restoration median ${value.restorationMedianMicros} μs, p95 ${value.restorationP95Micros} μs.", style = MaterialTheme.typography.bodySmall)
                Text("These examples do not prove complete detection in your own messages. Cloud answer quality, network latency, and GPU or NPU performance are not measured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (value.failures.isNotEmpty()) Text("Checks to review: ${value.failures.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
