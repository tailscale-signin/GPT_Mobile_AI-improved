package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class ConversationRoutingState(val features: AppFeatureSettings, val connections: List<ToolConnection>) {
    fun githubDestination(chatId: Int): Pair<GitHubConversationContext, ToolConnection>? {
        val context = features.conversationGitHub[chatId] ?: return null
        if (!features.isToolPluginEnabled(ToolPluginId.GITHUB) || !features.isToolPluginEnabled(ToolPluginId.connection(context.connectionUid))) return null
        val connection = connections.firstOrNull { it.connectionUid == context.connectionUid && it.type == ToolConnectionType.GITHUB } ?: return null
        return context to connection
    }
}

/** Resolve the destination before creating a chat controller, so only one controller can write its draft. */
@HiltViewModel
internal class ConversationRoutingViewModel @Inject constructor(settings: SettingRepository, connections: ToolConnectionDao) : ViewModel() {
    val state: StateFlow<ConversationRoutingState?> = combine(settings.observeFeatureSettings(), connections.observeConnections()) { features, current ->
        ConversationRoutingState(features, current)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
