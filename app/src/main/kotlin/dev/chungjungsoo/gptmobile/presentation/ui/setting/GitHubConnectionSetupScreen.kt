package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubDeviceAuthClient
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthConfiguration
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog

/** New chats may use a sole connection; an explicit selection never follows another account. */
@Composable
internal fun GitHubConnectionEntry(
    state: ToolConnectionsViewModel.ToolConnectionsUiState,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    setup: @Composable () -> Unit,
    connected: @Composable (ToolConnection) -> Unit
) {
    var selectedConnectionUid by rememberSaveable { mutableStateOf<String?>(null) }
    val candidates = gitHubModeConnections(state)
    val connection = gitHubModeConnection(state, selectedConnectionUid)
    LaunchedEffect(connection?.connectionUid) {
        if (selectedConnectionUid == null && connection != null) selectedConnectionUid = connection.connectionUid
    }
    when {
        connection != null -> key(connection.connectionUid) { connected(connection) }
        candidates.isNotEmpty() || selectedConnectionUid != null -> GitHubConnectionSelectionScreen(
            state = state,
            connections = candidates,
            selectionUnavailable = selectedConnectionUid != null,
            onSelect = { selectedConnectionUid = it },
            onBack = onBack,
            onManageConnections = onManageConnections
        )
        else -> setup()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubConnectionSelectionScreen(
    state: ToolConnectionsViewModel.ToolConnectionsUiState,
    connections: List<ToolConnection>,
    selectionUnavailable: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onManageConnections: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GitHubSetupOverview(
                    title = "Choose a GitHub connection",
                    description = if (selectionUnavailable) {
                        "Your selected connection is unavailable. Restore it in Manage connections or choose another connection for this chat."
                    } else {
                        "Choose the account and host for this chat. You’ll select its repository next."
                    }
                )
            }
            items(connections, key = { it.connectionUid }) { connection ->
                OutlinedButton(
                    onClick = { onSelect(connection.connectionUid) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("github_connection_${connection.connectionUid}")
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(connection.name, style = MaterialTheme.typography.titleSmall)
                        val account = state.gitHubAccounts[connection.connectionUid]?.login?.takeIf(String::isNotBlank)
                        Text(
                            listOfNotNull(account?.let { "@$it" }, gitHubConnectionHost(connection), connection.alias.takeIf(String::isNotBlank)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { TextButton(onClick = onManageConnections) { Text("Manage connections") } }
        }
    }
}

/** Connection setup stays inside GitHub mode and resumes the repository step after authorization. */
@Composable
internal fun GitHubConnectionSetupScreen(
    viewModel: ToolConnectionsViewModel,
    onBack: () -> Unit,
    onManageConnections: () -> Unit,
    onLaunchOAuth: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    var clientId by rememberSaveable { mutableStateOf(BuildConfig.GITHUB_OAUTH_CLIENT_ID) }
    val needsClientId = !GitHubPublicAuthConfiguration.project().configured && !GitHubDeviceAuthClient.validClientId(BuildConfig.GITHUB_OAUTH_CLIENT_ID)
    LaunchedEffect(viewModel, onLaunchOAuth) { viewModel.oauthLaunches.collect(onLaunchOAuth) }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    GitHubSetupWelcome(
        onConnect = { viewModel.connectGitHub(clientId) },
        onBack = onBack,
        isConnecting = state.isOAuthBusy,
        error = state.errorMessage,
        clientId = clientId.takeIf { needsClientId },
        onClientIdChange = { clientId = it },
        onManageConnections = onManageConnections
    )
    if (state.gitHubPublicSignInUid != null) {
        FadingAlertDialog(
            onDismissRequest = viewModel::cancelGitHubSignIn,
            title = { Text("Finish GitHub sign-in") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (state.gitHubAuthorizationResumable) "GitHub authorization is saved. Finish connecting from the retained sign-in ticket." else "Authorize the app in your browser. When you return, choose the repository and branch for this chat.")
                    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (state.gitHubAuthorizationResumable) {
                    TextButton(onClick = viewModel::resumeGitHubAuthorization, enabled = !state.isOAuthBusy) { Text(if (state.isOAuthBusy) "Connecting…" else "Finish connecting") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelGitHubSignIn, enabled = !state.isOAuthBusy) { Text("Cancel sign-in") } }
        )
    }
    state.gitHubSignIn?.let { signIn ->
        FadingAlertDialog(
            onDismissRequest = viewModel::cancelGitHubSignIn,
            title = { Text("Sign in with GitHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Open GitHub and enter this code to authorize API access. Check the app name and requested repository permissions.")
                    SelectionContainer { Text(signIn.userCode, style = MaterialTheme.typography.headlineMedium) }
                    Text("After sign-in, this screen continues to repository setup.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        GitHubDeviceAuthClient.requireTrustedVerificationUri(signIn.verificationUri)
                        uriHandler.openUri(signIn.verificationUri)
                    }.onFailure { viewModel.failOAuthLaunch() }
                }) { Text("Open GitHub") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelGitHubSignIn) { Text("Cancel") } }
        )
    }
}

/** A sole eligible account opens directly; multiple accounts require a selection. */
internal fun gitHubModeConnection(state: ToolConnectionsViewModel.ToolConnectionsUiState): ToolConnection? = gitHubModeConnection(state, null)
