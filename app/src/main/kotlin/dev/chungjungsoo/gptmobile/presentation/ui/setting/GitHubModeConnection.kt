package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId

/** Unauthenticated setup records and disabled connections never open the repository client. */
internal fun gitHubModeConnections(state: ToolConnectionsViewModel.ToolConnectionsUiState): List<ToolConnection> = state.connections.filter { connection ->
    connection.type == ToolConnectionType.GITHUB &&
        (state.pluginStates[ToolPluginId.GITHUB] ?: true) &&
        (state.pluginStates[ToolPluginId.connection(connection.connectionUid)] ?: true) &&
        (!connection.secretRef.isNullOrBlank() || connection.connectionUid in state.gitHubAccounts)
}

/** Account inventory order cannot choose a connection or replace an unavailable selection. */
internal fun gitHubModeConnection(state: ToolConnectionsViewModel.ToolConnectionsUiState, selectedConnectionUid: String?): ToolConnection? {
    val connections = gitHubModeConnections(state)
    return if (selectedConnectionUid == null) connections.singleOrNull() else connections.singleOrNull { it.connectionUid == selectedConnectionUid }
}

internal fun gitHubConnectionHost(connection: ToolConnection): String =
    runCatching { GitHubApiOrigins.fromEndpoint(connection.endpointUrl).hostIdentity }.getOrDefault("Host unavailable")
