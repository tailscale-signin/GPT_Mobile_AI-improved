package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayTranscriptDialog
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayConversationsScreen(
    onBack: () -> Unit,
    onOpenChat: (GatewayConversationAttachment) -> Unit,
    viewModel: GatewayConversationsViewModel = hiltViewModel()
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val selected = connections.firstOrNull { it.profileUid == state.connectionUid }
    LaunchedEffect(connections.map { it.profileUid to it.routeIdentity }) {
        val current = connections.firstOrNull { it.profileUid == state.connectionUid }
        if (current != null) {
            viewModel.select(current)
        } else if (state.connectionUid == null) {
            (connections.firstOrNull { it.availability.isConnected } ?: connections.firstOrNull())?.let(viewModel::select)
        }
    }
    LaunchedEffect(lifecycle, state.connectionUid) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                viewModel.refresh()
                awaitCancellation()
            } finally {
                viewModel.pause()
            }
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.gateway_browser_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.gateway_browser_back)) }
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.loading && state.connectionUid != null) { Text(stringResource(R.string.gateway_browser_refresh)) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("gateway_conversations"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "connection") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.gateway_browser_description), style = MaterialTheme.typography.titleMedium)
                    if (connections.isEmpty() && state.page == null) {
                        Text(stringResource(R.string.gateway_browser_no_connections))
                    } else if (connections.size > 1) {
                        Text(stringResource(R.string.gateway_browser_select), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            connections.forEach { connection ->
                                FilterChip(
                                    selected = connection.profileUid == state.connectionUid,
                                    onClick = { viewModel.select(connection) },
                                    label = {
                                        Column {
                                            Text(connection.name)
                                            Text(connection.host, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    selected?.let {
                        Text(it.name + " · " + it.host, style = MaterialTheme.typography.bodyMedium)
                        GatewayProfileStatus(it.availability)
                    }
                    state.lastSyncedAt?.let {
                        Text(stringResource(R.string.gateway_browser_last_synced, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.page?.principalId == "network:shared") {
                        Text(stringResource(R.string.gateway_browser_shared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.gateway_browser_loading), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.error != null) {
                        Text(
                            stringResource(
                                when (state.error) {
                                    GatewayBrowserError.OPEN -> R.string.gateway_browser_open_failed
                                    GatewayBrowserError.UNSUPPORTED -> R.string.gateway_browser_unsupported
                                    GatewayBrowserError.IDENTITY_CHANGED -> R.string.gateway_browser_identity_changed
                                    else -> R.string.gateway_browser_unavailable
                                }
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (state.page != null && state.page?.items.isNullOrEmpty() && !state.loading) {
                item(key = "empty") { Text(stringResource(R.string.gateway_browser_empty)) }
            }
            items(state.page?.items.orEmpty(), key = { it.conversationId }) { conversation ->
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("gateway_conversation_${conversation.conversationId}").clickable(
                        enabled = conversation.transcriptAvailable && state.openingId == null,
                        onClick = { viewModel.open(conversation, onOpenChat) }
                    ),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conversation.title.ifBlank { stringResource(R.string.gateway_browser_untitled) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (conversation.model.isNotBlank()) Text(conversation.model, style = MaterialTheme.typography.bodySmall)
                        val savedRef = conversation.sourceBranch?.takeIf(String::isNotBlank)
                            ?: conversation.branch?.takeIf(String::isNotBlank)
                            ?: conversation.sourceSha?.takeIf(String::isNotBlank)?.take(12)
                            ?: conversation.headSha?.takeIf(String::isNotBlank)?.take(12)
                            ?: conversation.base?.takeIf(String::isNotBlank)
                        listOfNotNull(conversation.repository, savedRef).filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        conversation.workingBranch?.takeIf { it != savedRef }?.let { Text(stringResource(R.string.gateway_jobs_working_branch, it), style = MaterialTheme.typography.bodySmall) }
                        if (!conversation.historyComplete) Text(conversation.historyLimitation ?: stringResource(R.string.gateway_transcript_history_partial), style = MaterialTheme.typography.bodySmall)
                        if (conversation.activeJobId != null) Text(stringResource(R.string.gateway_browser_active_job), style = MaterialTheme.typography.labelMedium)
                        if (!conversation.transcriptAvailable) Text(stringResource(R.string.gateway_browser_transcript_unavailable), style = MaterialTheme.typography.bodySmall)
                        if (state.openingId == conversation.conversationId) Text(stringResource(R.string.gateway_browser_opening), style = MaterialTheme.typography.bodySmall)
                        if (conversation.transcriptAvailable) {
                            TextButton(onClick = { viewModel.showTranscript(conversation) }, enabled = state.openingId == null) { Text(stringResource(R.string.gateway_jobs_saved_transcript)) }
                        }
                    }
                }
            }
            if (state.page?.hasMore == true) {
                item(key = "more") {
                    TextButton(onClick = { viewModel.refresh(loadMore = true) }, enabled = !state.loading) { Text(stringResource(R.string.gateway_browser_more)) }
                }
            }
        }
    }
    state.transcriptKey?.let { GatewayTranscriptDialog(it, onDismiss = viewModel::dismissTranscript) }
}
