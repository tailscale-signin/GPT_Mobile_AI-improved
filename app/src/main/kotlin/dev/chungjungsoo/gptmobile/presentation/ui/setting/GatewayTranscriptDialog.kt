package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog

/** Bounded retained parts remain readable even when their original execution profile is missing. */
@Composable
fun GatewayTranscriptDialog(conversationKey: String, onDismiss: () -> Unit, model: GatewayTranscriptViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(conversationKey) { model.open(conversationKey) }
    DisposableEffect(model) { onDispose { model.pause() } }
    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.page?.title ?: stringResource(R.string.gateway_transcript_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.gateway_transcript_read_only, state.profileName ?: stringResource(R.string.gateway_jobs_saved_profile)), style = MaterialTheme.typography.bodySmall)
                if (state.page?.historyComplete == false) Text(state.page?.historyLimitation ?: stringResource(R.string.gateway_transcript_history_partial), style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.loading) LinearProgressIndicator()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.gateway_jobs_close)) }
                    TextButton(onClick = model::reconnect, enabled = !state.loading) { Text(stringResource(R.string.gateway_transcript_reconnect)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = model::previous, enabled = state.previousCursors.isNotEmpty() && !state.loading) { Text(stringResource(R.string.gateway_transcript_previous)) }
                    TextButton(onClick = model::next, enabled = state.page?.nextCursor != null && !state.loading) { Text(stringResource(R.string.gateway_transcript_next)) }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.page?.items.orEmpty(), key = { it.messageId }) { row ->
                        var slice by remember(row.messageId, row.content) { mutableIntStateOf(0) }
                        val text = remember(row.content) { gatewayTranscriptSlices(row.content) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.gateway_transcript_part, row.role.replaceFirstChar(Char::titlecase), row.partIndex + 1, row.partCount), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(if (row.contentVerified) R.string.gateway_transcript_verified else R.string.gateway_transcript_unverified), style = MaterialTheme.typography.bodySmall)
                            SelectionContainer { Text(text[slice], style = MaterialTheme.typography.bodyMedium) }
                            if (text.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { slice-- }, enabled = slice > 0) { Text(stringResource(R.string.gateway_transcript_previous_text)) }
                                    Text(stringResource(R.string.gateway_transcript_text_page, slice + 1, text.size), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { slice++ }, enabled = slice + 1 < text.size) { Text(stringResource(R.string.gateway_transcript_next_text)) }
                                }
                            }
                        }
                    }
                    if (state.page?.items.isNullOrEmpty()) item { Text(stringResource(if (state.loading) R.string.gateway_transcript_loading else R.string.gateway_transcript_empty)) }
                }
            }
        }
    }
}

/** Slice bounded parts for Compose without cutting a Unicode surrogate pair in half. */
internal fun gatewayTranscriptSlices(content: String): List<String> {
    if (content.isEmpty()) return listOf("")
    val slices = mutableListOf<String>()
    var start = 0
    while (start < content.length) {
        var end = (start + 8192).coerceAtMost(content.length)
        if (end < content.length && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end++
        slices += content.substring(start, end)
        start = end
    }
    return slices
}
