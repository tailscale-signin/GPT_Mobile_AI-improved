package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.presentation.ui.setting.AppLogPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Exercises the production log projection and rows with bounded synthetic data. */
@Composable
internal fun AppLogBenchmarkFixture() {
    var entries by remember { mutableStateOf(emptyList<AppLogEntry>()) }
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.Default) { (0 until 2000).map(::fixtureEvent) }
        repeat(40) { batch ->
            delay(250)
            entries = withContext(Dispatchers.Default) {
                (entries + (2000 + batch * 20 until 2020 + batch * 20).map(::fixtureEvent)).takeLast(2000)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("benchmark_logs")) {
        if (entries.isNotEmpty()) item { Text("Live log fixture ready", Modifier.testTag("benchmark_logs_ready")) }
        item { AppLogPanel(entriesOverride = entries) }
    }
}

private fun fixtureEvent(index: Int) = AppLogEntry(
    time = 1_700_000_000_000L + index * 100,
    level = if (index % 17 == 0) "W" else "I",
    tag = if (index % 3 == 0) "Runtime" else "Worker",
    message = "Retained checkpoint event $index · nativeOperationId=benchmark-${index / 4} · phase=streaming " +
        if (index % 29 == 0) "A long structured diagnostic line. ".repeat(150) else "continuation saved",
    eventId = "benchmark-log-$index",
    monotonicNanos = index * 100_000_000L,
    bootMillis = index * 100L,
    processId = 1,
    processName = "benchmark"
)
