package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityStatus
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedChatState
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.diagnosticFrameTiming
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayTranscriptDialog
import java.text.DateFormat
import java.util.Date

/** The same home navigation, toolbar, chat rows and compose button, scoped to saved gateway work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayConversationsScreen(
    onBack: () -> Unit,
    onOpenChat: (GatewayConversationAttachment) -> Unit,
    onOpenLocalChat: (ChatRoomV2) -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    onManageProfiles: () -> Unit,
    viewModel: GatewayConversationsViewModel = hiltViewModel()
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val selected = connections.firstOrNull { it.profileUid == state.connectionUid }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val localChats = remember(state.localChats, query) {
        state.localChats.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    }
    val remoteChats = remember(state.page, state.localChats, state.localConversationChats, query) {
        state.unmirroredConversations.filter {
            query.isBlank() || listOf(it.title, it.model, it.repository.orEmpty()).any { text -> text.contains(query.trim(), ignoreCase = true) }
        }
    }
    val listState = HomeViewModel.ChatListState(chats = localChats, isSearchMode = searching)
    LaunchedEffect(connections.map { it.profileUid to it.navigationIdentity }) {
        viewModel.updateConnections(connections)
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                viewModel.observeVisible()
            } finally {
                viewModel.pause()
            }
        }
    }
    Scaffold(
        modifier = Modifier.diagnosticFrameTiming("GatewayChats").nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopBar(
                chatListState = listState,
                scrollBehavior = scrollBehavior,
                selectedChatCount = 0,
                canDuplicate = false,
                onCloseSelectionMode = {},
                onDuplicateClick = {},
                onDeleteClick = {},
                onSettingClick = onSettings,
                onSearchToggle = {
                    searching = !searching
                    if (!searching) query = ""
                },
                onSearchQueryChanged = { query = it },
                searchQuery = query
            )
        },
        floatingActionButton = {
            if (!searching) NewChatButton { if (!state.creatingChat) viewModel.newChat(onOpenLocalChat) }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!searching) {
                HomeNavigationTabs(
                    selected = HomeDestination.GATEWAY,
                    onChats = onBack,
                    onGateway = {},
                    onFavorites = onFavorites
                )
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("gateway_conversations"),
                contentPadding = PaddingValues(bottom = 104.dp)
            ) {
                item(key = "gateway_status") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = { details = true },
                            label = {
                                val status = when (selected?.availability?.status) {
                                    GatewayAvailabilityStatus.CHECKING -> "Connecting"
                                    GatewayAvailabilityStatus.OFFLINE -> "Offline"
                                    GatewayAvailabilityStatus.ONLINE, GatewayAvailabilityStatus.BUSY_QUEUE -> "Connected"
                                    else -> "Needs attention"
                                }
                                Text(listOfNotNull(selected?.name, status).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingIcon = { Icon(Icons.Rounded.CloudQueue, null) }
                        )
                        if (state.loading || state.creatingChat) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.creationError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = onManageProfiles) { Text(stringResource(R.string.gateway_home_profiles)) }
                                TextButton(onClick = viewModel::clearCreationError) { Text(stringResource(R.string.gateway_home_dismiss)) }
                            }
                        }
                        if (state.error == GatewayBrowserError.OPEN || state.error == GatewayBrowserError.IDENTITY_CHANGED) {
                            Text(
                                stringResource(if (state.error == GatewayBrowserError.OPEN) R.string.gateway_browser_open_failed else R.string.gateway_browser_identity_changed),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (state.cachedInventory && state.page != null) {
                            val observationTime = remember(state.lastSyncedAt) { state.lastSyncedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } }
                            Text(
                                if (observationTime == null) stringResource(R.string.gateway_home_saved_history) else stringResource(R.string.gateway_home_saved_history_at, observationTime),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                itemsIndexed(localChats, key = { _, chat -> "local:${chat.id}" }) { index, chat ->
                    val profile = state.profileChoices.firstOrNull { it.profileUid in chat.activePlatform }
                    val queue = state.gatewayStates[chat.id]
                    val serverWorking = !state.cachedInventory &&
                        state.page?.let { page ->
                            page.items.any { conversation ->
                                conversation.isWorking() && state.localConversationChats[gatewayIdentityKey(page.gatewayId, page.principalId, conversation.conversationId)] == chat.id
                            }
                        } == true
                    Column(Modifier.testTag("gateway_chat_${chat.id}")) {
                        ChatListItem(
                            chatRoom = chat,
                            idx = index,
                            chatListState = listState,
                            isGenerating = chat.id in state.activeChatIds || queue?.state == GatewayQueuedState.RUNNING || serverWorking,
                            hasUnreadResponse = chat.id in state.unreadChatIds,
                            usingPlatform = profile?.name ?: stringResource(R.string.gateway_menu_title),
                            profileLabels = emptyList(),
                            isGateway = true,
                            isServerChat = true,
                            onItemClick = { viewModel.openLocalChat(chat, onOpenLocalChat) },
                            onItemLongClick = { viewModel.openLocalChat(chat, onOpenLocalChat) }
                        )
                        val queued = state.queuedCounts[chat.id] ?: 0
                        if (queue != null || queued > 0) {
                            Text(
                                queue?.let { gatewayQueuedLabel(it) } ?: stringResource(R.string.gateway_home_chat_queued, queued),
                                Modifier.padding(start = 72.dp, end = 16.dp, bottom = 10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (queue?.state == GatewayQueuedState.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (localChats.isEmpty() && remoteChats.isEmpty() && !state.loading) {
                    item(key = "empty") {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(if (query.isBlank()) R.string.gateway_home_empty else R.string.gateway_home_no_matches),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!searching) {
                                Button(onClick = { viewModel.newChat(onOpenLocalChat) }, enabled = !state.creatingChat) {
                                    Text("New Gateway chat")
                                }
                            }
                        }
                    }
                }
                items(remoteChats, key = { "remote:${it.conversationId}" }) { conversation ->
                    val locallySaved = state.page?.let { page ->
                        gatewayIdentityKey(page.gatewayId, page.principalId, conversation.conversationId) in state.localConversationKeys
                    } == true
                    GatewayHistoryRow(
                        conversation,
                        opening = state.openingId == conversation.conversationId,
                        live = !state.cachedInventory,
                        enabled = state.openingId == null,
                        canOpen = conversation.transcriptAvailable || locallySaved,
                        onOpen = { viewModel.open(conversation, onOpenChat) },
                        onTranscript = { viewModel.showTranscript(conversation) }
                    )
                }
                if (state.page?.hasMore == true) {
                    item(key = "more") {
                        TextButton(onClick = { viewModel.refresh(loadMore = true) }, enabled = !state.loading, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.gateway_browser_more))
                        }
                    }
                }
            }
        }
    }
    if (details) {
        ModalBottomSheet(onDismissRequest = { details = false }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gateway connection", style = MaterialTheme.typography.titleLarge)
                selected?.let { GatewaySelectedConnectionHeader(it.name, it.availability) }
                if (connections.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        connections.forEach { connection ->
                            FilterChip(connection.profileUid == state.connectionUid, { viewModel.select(connection) }, { Text(connection.name) })
                        }
                    }
                }
                if (state.error == GatewayBrowserError.UNSUPPORTED) Text(stringResource(R.string.gateway_browser_unsupported))
                val waiting = state.localChats.sumOf { state.queuedCounts[it.id] ?: 0 }
                if (waiting > 0) Text(stringResource(R.string.gateway_home_queued_count, waiting))
                Row {
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.loading && selected != null) {
                        Text(stringResource(R.string.gateway_browser_refresh))
                    }
                    TextButton(onClick = {
                        details = false
                        onManageProfiles()
                    }) {
                        Text(stringResource(R.string.gateway_home_profiles))
                    }
                }
            }
        }
    }
    if (state.profilePickerVisible) {
        ModalBottomSheet(onDismissRequest = viewModel::dismissProfilePicker) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(stringResource(R.string.gateway_home_choose_profile), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.gateway_home_choose_hint), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("gateway_profile_picker")) {
                    items(state.profileChoices, key = { it.profileUid }) { profile ->
                        val observation = connections.firstOrNull {
                            it.profileUid == profile.profileUid && it.model == profile.model && it.configurationRevision == profile.configurationRevision
                        }?.availability
                        ListItem(
                            modifier = Modifier.fillMaxWidth().testTag("gateway_profile_${profile.profileUid}").clickable(enabled = !state.creatingChat) { viewModel.chooseProfile(profile.profileUid, onOpenLocalChat) },
                            headlineContent = { Text(profile.name) },
                            supportingContent = {
                                Column {
                                    Text(profile.model)
                                    if (observation != null) GatewayProfileStatus(observation) else Text(gatewayAvailabilityLabel(profile.status), style = MaterialTheme.typography.labelMedium)
                                }
                            },
                            leadingContent = { Icon(Icons.Rounded.CloudQueue, contentDescription = null) }
                        )
                    }
                }
                TextButton(onClick = onManageProfiles, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.gateway_home_profiles)) }
            }
        }
    }
    state.transcriptKey?.let { GatewayTranscriptDialog(it, onDismiss = viewModel::dismissTranscript) }
}

/** Keep the selected host readable when a recovery paragraph needs the full phone width. */
@Composable
internal fun GatewaySelectedConnectionHeader(name: String, availability: GatewayAvailability, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth().testTag("gateway_selected_connection_name"))
        GatewayProfileStatus(availability, Modifier.fillMaxWidth().testTag("gateway_selected_connection_status"))
    }
}

@Composable
private fun gatewayQueuedLabel(value: GatewayQueuedChatState): String = stringResource(
    when (value.state) {
        GatewayQueuedState.QUEUED -> R.string.gateway_home_queue_waiting_turn
        GatewayQueuedState.WAITING_FOR_GATEWAY -> R.string.gateway_home_queue_waiting_connection
        GatewayQueuedState.RUNNING -> R.string.gateway_home_queue_running
        GatewayQueuedState.RECONCILING -> R.string.gateway_home_queue_reconciling
        GatewayQueuedState.BLOCKED -> R.string.gateway_home_queue_blocked
    }
)

@Composable
private fun GatewayHistoryRow(conversation: GatewayConversationSnapshot, opening: Boolean, live: Boolean, enabled: Boolean, canOpen: Boolean, onOpen: () -> Unit, onTranscript: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("gateway_conversation_${conversation.conversationId}")
            .clickable(enabled = enabled && canOpen, onClick = onOpen),
        headlineContent = { Text(conversation.title.ifBlank { stringResource(R.string.gateway_browser_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                GatewayConversationIcon(working = live && conversation.isWorking(), unread = false)
            }
        },
        supportingContent = {
            val label = when {
                opening -> stringResource(R.string.gateway_browser_opening)
                live && conversation.jobState in setOf("QUEUED", "PENDING") -> "Queued"
                live && conversation.isWorking() -> "Running"
                !conversation.transcriptAvailable -> stringResource(R.string.gateway_browser_transcript_unavailable)
                !conversation.historyComplete -> stringResource(R.string.gateway_transcript_history_partial)
                !live -> stringResource(R.string.gateway_home_saved_history)
                else -> listOfNotNull(conversation.repository, conversation.sourceBranch ?: conversation.branch)
                    .filter(String::isNotBlank).joinToString(" · ")
            }
            val timestamp = remember(conversation.updatedAt) {
                conversation.updatedAt.takeIf { it.isFinite() && it > 0 }
                    ?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((it * 1_000).toLong())) }
            }
            Text(listOfNotNull(label.takeIf(String::isNotBlank), timestamp).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (conversation.transcriptAvailable) {
                IconButton(onClick = onTranscript, enabled = enabled) {
                    Icon(Icons.Rounded.Description, contentDescription = stringResource(R.string.gateway_jobs_saved_transcript))
                }
            }
        }
    )
}
