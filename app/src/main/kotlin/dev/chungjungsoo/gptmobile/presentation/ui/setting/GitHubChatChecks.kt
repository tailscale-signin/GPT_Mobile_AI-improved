package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatchRepository
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class GitHubChatChecksViewModel @Inject constructor(private val watches: GitHubCheckWatchRepository) : ViewModel() {
    internal fun observe(chatId: Int) = watches.observe(chatId)
    fun cancel(id: String) {
        viewModelScope.launch { watches.cancel(id) }
    }
}

@Composable
internal fun GitHubChatChecks(chatId: Int, model: GitHubChatChecksViewModel = hiltViewModel()) {
    val watches by remember(model, chatId) { model.observe(chatId) }.collectAsStateWithLifecycle(emptyList())
    if (watches.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(watches, key = { it.id }) { watch ->
            Card(Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("GitHub checks · ${watch.status}", style = MaterialTheme.typography.labelLarge)
                    Text(watch.detail, style = MaterialTheme.typography.bodySmall)
                    if (!watch.terminal) TextButton(onClick = { model.cancel(watch.id) }) { Text("Stop watching") }
                }
            }
        }
    }
}
