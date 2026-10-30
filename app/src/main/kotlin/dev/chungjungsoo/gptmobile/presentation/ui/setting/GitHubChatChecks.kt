package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatch
import dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatchRepository
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GitHubChatChecksViewModel @Inject constructor(private val watches: GitHubCheckWatchRepository) : ViewModel() {
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError = _refreshError.asStateFlow()
    internal fun observe(chatId: Int) = watches.observe(chatId)
    fun refresh(id: String) {
        viewModelScope.launch {
            _refreshError.value = null
            try {
                watches.refresh(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _refreshError.value = "Could not refresh checks. Review GitHub connection and repository access."
            }
        }
    }
    fun cancel(id: String) {
        viewModelScope.launch { watches.cancel(id) }
    }
}

@Composable
internal fun GitHubChatChecks(chatId: Int, model: GitHubChatChecksViewModel = hiltViewModel()) {
    val watches by remember(model, chatId) { model.observe(chatId) }.collectAsStateWithLifecycle(emptyList())
    if (watches.isEmpty()) return
    val refreshError by model.refreshError.collectAsStateWithLifecycle()
    refreshError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
    val uriHandler = LocalUriHandler.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 24.dp).coerceAtLeast(0.dp)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(watches, key = { it.id }) { watch ->
                GitHubCheckWatchCard(
                    watch = watch,
                    onOpen = {
                        val path = watch.pullNumber?.let { "pull/$it" } ?: "actions/runs/${watch.runId}"
                        uriHandler.openUri("https://github.com/${watch.owner}/${watch.repo}/$path")
                    },
                    onRefresh = { model.refresh(watch.id) },
                    onStop = { model.cancel(watch.id) },
                    modifier = Modifier.width(cardWidth)
                )
            }
        }
    }
}

internal fun gitHubWatchBrief(watch: GitHubCheckWatch): String = when {
    watch.health?.state == "MERGED" || watch.status == "MERGED" -> "Merged. Watching complete."
    watch.health?.state == "CLOSED" || watch.status == "CLOSED" -> "PR closed."
    watch.status == "FAILED" -> "Checks need attention."
    watch.status == "PASSED" -> "Checks passed."
    watch.status == "RETRYING" -> "Reconnecting to GitHub."
    watch.status == "STALE" -> "New revision available."
    watch.status == "BLOCKED" -> "GitHub access needs attention."
    watch.status == "CANCELLED" -> "Watching stopped."
    watch.status == "EXPIRED" -> "Watch expired."
    watch.status == "SKIPPED" -> "Checks were skipped."
    watch.status in setOf("NONE", "UNKNOWN") -> "Waiting for check results."
    else -> "Checking build and tests."
}

@Composable
internal fun GitHubCheckWatchCard(
    watch: GitHubCheckWatch,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDetails by remember(watch.id) { mutableStateOf(false) }
    val health = watch.health
    val color = if (watch.status == "FAILED" || health?.state in setOf("FAILED", "CONFLICTS")) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val title = watch.pullNumber?.let { "PR #$it" } ?: "Run #${watch.runId}"
    Card(modifier.widthIn(min = 280.dp, max = 400.dp), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(gitHubWatchBrief(watch), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(modifier = Modifier.weight(1f), onClick = { showDetails = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Details", maxLines = 1) }
                TextButton(modifier = Modifier.weight(1f), onClick = onOpen, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("GitHub", maxLines = 1) }
                TextButton(
                    modifier = Modifier.weight(1f),
                    onClick = if (watch.terminal) onRefresh else onStop,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(if (watch.terminal) "Refresh" else "Stop", maxLines = 1) }
            }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("$title · Details") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${watch.owner}/${watch.repo}")
                    Text(gitHubWatchBrief(watch))
                    Text(watch.detail)
                    Text("Revision ${watch.headSha.take(12)}")
                    health?.let {
                        Text("Merge: ${it.state.lowercase().replace('_', ' ')}")
                        Text(it.detail)
                        Text("Review: ${it.review.lowercase().replace('_', ' ')}")
                        Text("Observed required checks: ${it.requiredChecks?.toString() ?: "Unknown"}")
                        it.failingChecks.forEach { name -> Text("Failed: $name") }
                    }
                    Text("Checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(watch.updatedAt))}")
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Done") } }
        )
    }
}
