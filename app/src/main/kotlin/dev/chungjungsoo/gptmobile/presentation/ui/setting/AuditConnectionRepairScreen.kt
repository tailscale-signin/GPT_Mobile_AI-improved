package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.audit.safeAuditDiagnosticField
import dev.chungjungsoo.gptmobile.data.audit.safeAuditFailurePhase
import dev.chungjungsoo.gptmobile.data.audit.safeAuditReason
import dev.chungjungsoo.gptmobile.data.audit.safeAuditValidationRule
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.util.PERMISSION_ACCESS_LOCAL_NETWORK
import dev.chungjungsoo.gptmobile.util.requiresLocalNetworkAccess

/** Opening a historical result never dispatches a probe or selects a different connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditConnectionRepairScreen(
    connectionUid: String,
    viewModel: ToolConnectionsViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onLaunchOAuth: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = state.connections.singleOrNull { it.connectionUid == connectionUid }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = permissionAction
        permissionAction = null
        if (granted) action?.invoke() else permissionNotice = "Local network access is required to check this saved connection. No check was sent."
    }
    fun useSavedConnection(saved: ToolConnection, action: (ToolConnection) -> Unit) {
        permissionNotice = null
        val guardedAction = {
            if (state.connections.singleOrNull { it.connectionUid == saved.connectionUid } == saved &&
                viewModel.uiState.value.connections.singleOrNull { it.connectionUid == saved.connectionUid } == saved
            ) {
                action(saved)
            } else {
                permissionNotice = "The saved connection changed. Review its current settings before checking again."
            }
        }
        if (saved.endpointUrl?.let(::requiresLocalNetworkAccess) == true &&
            Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionAction = guardedAction
            permissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
        } else {
            guardedAction()
        }
    }
    LaunchedEffect(viewModel, onLaunchOAuth) { viewModel.oauthLaunches.collect(onLaunchOAuth) }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(probeHealth = false) }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Connection checks") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AuditConnectionRepairContent(
                connection = connection,
                health = state.connectionHealth[connectionUid],
                busy = state.isOAuthBusy || permissionAction != null,
                onEdit = { connection?.let { onEdit(it.connectionUid) } },
                onCheck = { connection?.let { useSavedConnection(it, viewModel::probeConnection) } },
                onSignIn = { connection?.let { useSavedConnection(it) { saved -> viewModel.startOAuth(saved.connectionUid, expectedConnection = saved) } } },
                onRefresh = { viewModel.refresh(probeHealth = false) }
            )
            permissionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.errorMessage?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::clearError) { Text("Dismiss message") }
            }
        }
    }
}

@Composable
internal fun AuditConnectionRepairContent(
    connection: ToolConnection?,
    health: ToolConnectionHealth?,
    busy: Boolean,
    onEdit: () -> Unit,
    onCheck: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    if (connection == null) {
        Text("This saved connection is no longer available. Its historical audit evidence is retained. A connection with the same name will not replace it.")
        return
    }
    val checking = health?.status == ToolConnectionHealthStatus.CHECKING
    Text(connection.name, style = MaterialTheme.typography.headlineSmall)
    Text("Review the current saved connection, correct its settings, then request a fresh check. Earlier audit results remain unchanged.")
    onRefresh?.let { refresh -> TextButton(onClick = refresh, enabled = !busy) { Text("Refresh saved settings") } }
    OutlinedButton(onClick = onEdit, enabled = !busy && !checking) { Text("Edit saved connection") }
    if (connection.type == ToolConnectionType.MCP) {
        Text("A fresh connection check uses bounded requests to negotiate the MCP protocol and read discovery metadata. It does not establish that individual tools work.", style = MaterialTheme.typography.bodySmall)
        val credentialBlocked = health?.errorCode in setOf("MCP_LOCAL_CREDENTIAL_MISSING", "MCP_LOCAL_CREDENTIAL_INVALID")
        if (credentialBlocked) {
            Text("Replace the saved credential or sign in again before requesting another check. The credential failure was local; it did not send an MCP request.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onCheck, enabled = !busy && !checking && !credentialBlocked) { Text(if (checking) "Checking connection…" else "Reconnect and check again") }
        if (connection.authType == ToolConnectionAuthType.OAUTH) {
            OutlinedButton(onClick = onSignIn, enabled = !busy && !checking) { Text("Sign in again") }
        }
        health?.let {
            Text(it.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            it.errorCode?.let { code ->
                Text("Diagnostic code: ${safeAuditReason(code)}", style = MaterialTheme.typography.bodySmall)
                Text(appAuditRetryabilityText(it.retryable), style = MaterialTheme.typography.bodySmall)
            }
            it.checkedAt?.let { time -> Text("Connection check: ${benchmarkResultDate(time)}", style = MaterialTheme.typography.bodySmall) }
            safeAuditFailurePhase(it.failurePhase)?.let { phase -> Text("Failure phase: ${phase.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            safeAuditValidationRule(it.validationRule)?.let { rule -> Text("Validation rule: $rule", style = MaterialTheme.typography.bodySmall) }
            safeAuditDiagnosticField(it.validationField)?.let { field -> Text("Validation field: $field", style = MaterialTheme.typography.bodySmall) }
            it.httpStatus?.takeIf { status -> status in 100..599 }?.let { status -> Text("HTTP status: $status", style = MaterialTheme.typography.bodySmall) }
            it.dispatchEvidence?.takeIf { value -> value in setOf("NOT_DISPATCHED", "ATTEMPTED", "CONFIRMED", "UNKNOWN") }?.let { value -> Text("Dispatch: ${value.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            it.observedAtEpochMs?.let { time -> Text("Original failure observation: ${benchmarkResultDate(time)}", style = MaterialTheme.typography.bodySmall) }
            it.configurationRevision?.takeIf { revision -> revision.matches(Regex("[a-f0-9]{64}")) }?.let { revision -> Text("Observed configuration revision: ${revision.take(12)}", style = MaterialTheme.typography.bodySmall) }
        }
        Text("After discovery succeeds, exercise an authorized representative tool with an expected result. Then return to App audit and start a fresh run. The new audit records discovery separately from tool execution.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Use this integration's settings and supported test action to verify its intended operation. A configuration check alone does not verify execution.")
    }
}
