package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.github.GitHubDeviceAuthClient
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthConfiguration
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog

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

/** Unauthenticated setup records must never open the repository client. */
internal fun gitHubModeConnection(state: ToolConnectionsViewModel.ToolConnectionsUiState): dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection? = state.connections.firstOrNull { connection ->
    connection.type == dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType.GITHUB &&
        (state.pluginStates[dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB] ?: true) &&
        (state.pluginStates[dev.chungjungsoo.gptmobile.data.model.ToolPluginId.connection(connection.connectionUid)] ?: true) &&
        (!connection.secretRef.isNullOrBlank() || connection.connectionUid in state.gitHubAccounts)
}
