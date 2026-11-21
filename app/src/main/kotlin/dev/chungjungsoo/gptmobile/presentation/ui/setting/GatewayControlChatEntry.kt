package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The chat card observes and writes the same store as Settings, using the explicitly selected Gateway. */
@Composable
internal fun GatewayControlChatEntry(profile: PlatformV2, model: GatewayControlViewModel = hiltViewModel()) {
    val states by model.states.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val state = states[profile.uid]
    val snapshot = state?.snapshot
    var details by remember(profile.uid) { mutableStateOf(false) }
    var confirmBinding by remember(profile.uid) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(profile, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refresh(profile)
                delay(5_000)
            }
        }
    }
    val direct by model.direct.collectAsStateWithLifecycle()
    direct[profile.uid to profile.apiUrl]?.takeIf { state?.phase == GatewayControlPhase.UNPAIRED }?.let { admission ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (admission.enabled) "Engineering on" else "Engineering off", style = MaterialTheme.typography.bodySmall)
                }
                if (admission.principalId.startsWith("tailnet:")) {
                    Switch(admission.enabled, onCheckedChange = { model.toggleDirect(profile) }, enabled = !busy)
                }
            }
        }
        return
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name.take(120), style = MaterialTheme.typography.titleMedium)
                    Text("Gateway Engineering", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = snapshot?.desired?.get("engineering_enabled") == JsonPrimitive(true),
                    onCheckedChange = { enabled ->
                        if (enabled && state?.engineeringBindingConfirmed != true) {
                            confirmBinding = true
                        } else if (snapshot != null) {
                            model.apply(profile, snapshot.revision, JsonObject(mapOf("engineering_enabled" to JsonPrimitive(enabled))))
                        }
                    },
                    enabled = state?.writable == true && !busy && snapshot?.writableFields?.contains("engineering_enabled") == true,
                    modifier = Modifier.testTag("chat_gateway_owner_engineering")
                )
            }
            Text(
                when {
                    state == null -> "Checking the selected Gateway…"
                    state.phase == GatewayControlPhase.UNPAIRED -> "Connect using the PC’s Gateway address in Gateway settings."
                    state.phase == GatewayControlPhase.PAIRING -> "Waiting for PC confirmation."
                    state.phase == GatewayControlPhase.REVOKED -> "This phone's access was revoked."
                    state.phase != GatewayControlPhase.LIVE -> state.message ?: "Last confirmed state · refresh Gateway settings."
                    !state.engineeringBindingConfirmed && snapshot?.desired?.get("engineering_enabled") == JsonPrimitive(true) -> "PC gate: On · confirm this phone's owner binding before new jobs."
                    snapshot?.effective?.get("engineering_enabled") == JsonPrimitive(true) -> "Effective: On · existing jobs keep their original identity."
                    snapshot?.desired?.get("engineering_enabled") == JsonPrimitive(true) -> "Requested: On · complete the PC prerequisites in Gateway settings."
                    else -> "Effective: Off · new owner jobs are paused."
                },
                style = MaterialTheme.typography.bodySmall
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state?.writable == true && !state.engineeringBindingConfirmed && snapshot?.desired?.get("engineering_enabled") == JsonPrimitive(true)) {
                TextButton(onClick = { confirmBinding = true }, enabled = !busy) { Text("Apply Engineering to this phone") }
            }
            OutlinedButton(onClick = { details = true }) { Text("Gateway settings") }
        }
    }
    if (details) GatewayControlSettingsDialog(onDismiss = { details = false }, initialProfileUid = profile.uid, model = model)
    if (confirmBinding && snapshot != null) {
        AlertDialog(
            onDismissRequest = { confirmBinding = false },
            title = { Text("Apply Engineering to ${profile.name.take(80)}?") },
            text = { Text("New engineering work will use your paired owner on ${state?.targetEndpoint ?: profile.name} (installation ${snapshot.installationId.takeLast(12)}). Your inference selection stays separate. Repository permissions and PC prerequisites still apply.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBinding = false
                    model.apply(profile, snapshot.revision, JsonObject(mapOf("engineering_enabled" to JsonPrimitive(true))), confirmEngineering = true)
                }, enabled = state?.writable == true && !busy) { Text("Apply Engineering") }
            },
            dismissButton = { TextButton(onClick = { confirmBinding = false }) { Text("Cancel") } }
        )
    }
}
