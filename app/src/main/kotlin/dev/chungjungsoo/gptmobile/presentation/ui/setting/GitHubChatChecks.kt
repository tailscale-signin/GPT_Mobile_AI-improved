package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatchRepository
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
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(watches, key = { it.id }) { watch ->
            val health = watch.health
            val color = when {
                watch.status == "FAILED" || health?.state in setOf("FAILED", "CONFLICTS") -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            Card(Modifier.widthIn(min = 260.dp, max = 360.dp), colors = CardDefaults.cardColors(containerColor = color)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${watch.owner}/${watch.repo}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Checks: ${watch.status.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Text(watch.detail, style = MaterialTheme.typography.bodySmall)
                    if (!watch.terminal) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (health != null) {
                        HorizontalDivider()
                        Text("Merge: ${health.state.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelLarge)
                        Text(health.detail, style = MaterialTheme.typography.bodySmall)
                        Text("Review: ${health.review.lowercase().replace('_', ' ')} · ${health.requiredChecks} observed required checks", style = MaterialTheme.typography.bodySmall)
                        health.failingChecks.take(3).forEach { Text("Failed: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("Checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(watch.updatedAt))}", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val path = watch.pullNumber?.let { "pull/$it" } ?: "actions/runs/${watch.runId}"
                        TextButton(onClick = { uriHandler.openUri("https://github.com/${watch.owner}/${watch.repo}/$path") }) { Text("Open GitHub") }
                        if (watch.terminal) {
                            TextButton(onClick = { model.refresh(watch.id) }) { Text("Refresh") }
                        } else {
                            TextButton(onClick = { model.cancel(watch.id) }) { Text("Stop watching") }
                        }
                    }
                }
            }
        }
    }
}
