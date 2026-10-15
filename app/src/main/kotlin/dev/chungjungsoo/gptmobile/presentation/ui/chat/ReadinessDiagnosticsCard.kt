package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticEvent
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Response-scoped, opt-in journal data only. Expanding details never starts preparation. */
@Composable
internal fun ReadinessDiagnosticsCard(runId: String?, running: Boolean) {
    var events by remember(runId) { mutableStateOf<List<RunDiagnosticEvent>>(emptyList()) }
    LaunchedEffect(runId, running) {
        if (runId == null) return@LaunchedEffect
        val journal = RunDiagnostics.journal ?: return@LaunchedEffect
        events = try {
            withContext(Dispatchers.IO) { journal.snapshot(setOf(runId)).events }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (events.isEmpty()) return
    Column {
        Text("Preparation & Readiness", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        events.firstOrNull { it.kind in setOf(RunDiagnosticKind.PREPARATION_DIRECT, RunDiagnosticKind.PREPARATION_CONTEXTUAL, RunDiagnosticKind.PREPARATION_REQUIRED) }?.let {
            Text("Plan: ${it.kind.name.removePrefix("PREPARATION_")}", style = MaterialTheme.typography.bodySmall)
        }
        events.lastOrNull { it.kind == RunDiagnosticKind.CACHE_OBSERVATION }?.let { Text("Tool cache: ${it.cacheHits ?: 0} hits · ${it.cacheMisses ?: 0} misses · ${it.staleCacheReads ?: 0} stale metadata reads", style = MaterialTheme.typography.bodySmall) }
        val phases = events.filter { it.kind == RunDiagnosticKind.PREPARATION_PHASE }.groupBy { it.phase }
        phases.forEach { (phase, samples) ->
            Text("${phase?.name?.replace('_', ' ') ?: "Preparation"}: ${samples.sumOf { it.elapsedMillis ?: 0 }} ms · ${samples.count { it.successful == false }} unsuccessful", style = MaterialTheme.typography.bodySmall)
        }
        val firstText = events.firstOrNull { it.kind == RunDiagnosticKind.FIRST_TEXT && it.role in setOf(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.PRIMARY, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.CONTRIBUTOR, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticRole.SYNTHESIS) }
        val visible = events.firstOrNull { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT }
        if (firstText?.processSession == visible?.processSession && firstText?.monotonicNanos != null && visible?.monotonicNanos != null && visible.monotonicNanos >= firstText.monotonicNanos) {
            Text("First parsed answer to first draw: ${((visible.monotonicNanos - firstText.monotonicNanos) / 1_000_000).coerceAtLeast(0)} ms", style = MaterialTheme.typography.bodySmall)
        }
        Text("${events.count { it.kind == RunDiagnosticKind.HTTP_CALL_START }} observed HTTP attempts · ${events.count { it.kind == RunDiagnosticKind.REQUEST_BODY_END }} body writes completed · ${events.count { it.kind == RunDiagnosticKind.RESPONSE_HEADERS }} response headers", style = MaterialTheme.typography.bodySmall)
        events.lastOrNull { it.kind == RunDiagnosticKind.PROVIDER_COOLDOWN }?.let { Text("Provider cooldown observed: ${it.elapsedMillis ?: 0} ms", style = MaterialTheme.typography.bodySmall) }
    }
}
