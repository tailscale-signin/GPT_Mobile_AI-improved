package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlField
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayControlState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairingLink
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The same owner controls open from GitHub chat and from Settings > Gateway. */
@Composable
internal fun GatewayControlSettingsDialog(onDismiss: () -> Unit, initialProfileUid: String? = null, initialPairingLink: String? = null, model: GatewayControlViewModel = hiltViewModel()) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val states by model.states.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var selected by rememberSaveable(initialProfileUid, initialPairingLink) { mutableStateOf(initialProfileUid) }
    var menu by remember { mutableStateOf(false) }
    val linkedAddress = remember(initialPairingLink) {
        initialPairingLink?.let { runCatching { GatewayPairingLink.parse(it, System.currentTimeMillis() / 1000).profileEndpoint }.getOrNull() }
    }
    val profile = when {
        selected != null -> profiles.singleOrNull { it.uid == selected }
        linkedAddress != null -> profiles.singleOrNull { it.apiUrl.trimEnd('/') == linkedAddress }
        else -> profiles.singleOrNull()
    }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(profile, lifecycle) {
        if (profile != null) {
            lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    model.refresh(profile)
                    delay(5_000)
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gateway settings") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (profiles.isNotEmpty() && linkedAddress == null) {
                    Box {
                        OutlinedButton(onClick = { menu = true }, enabled = !busy) { Text(profile?.name?.take(120) ?: "Choose Gateway connection") }
                        DropdownMenu(menu, { menu = false }) {
                            profiles.forEach { candidate ->
                                DropdownMenuItem(text = { Text(candidate.name.take(120)) }, onClick = {
                                    selected = candidate.uid
                                    model.clearError()
                                    menu = false
                                })
                            }
                        }
                    }
                }
                if (linkedAddress != null && profile == null) {
                    Text(linkedAddress)
                    OutlinedButton(onClick = { model.connectAddress(linkedAddress, initialPairingLink) { selected = it } }, enabled = !busy) { Text("Connect to this PC") }
                } else if (profiles.isEmpty() && initialPairingLink == null) {
                    var address by rememberSaveable { mutableStateOf("") }
                    Text("On your PC, open Connect phone and copy the Gateway address.")
                    OutlinedTextField(address, { address = it.take(512) }, label = { Text("Gateway address") }, singleLine = true)
                    OutlinedButton(onClick = { model.connectAddress(address) { selected = it } }, enabled = address.isNotBlank() && !busy) { Text("Connect") }
                } else if (profiles.isEmpty()) {
                    Text("Add this PC’s private Gateway address in AI Profiles, then reopen the connection link.")
                }
                profile?.let {
                    val direct by model.direct.collectAsStateWithLifecycle()
                    val admission = direct[it.uid to it.apiUrl]?.takeIf { _ -> states[it.uid]?.phase == GatewayControlPhase.UNPAIRED && initialPairingLink == null }
                    if (admission != null) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Text(if (admission.enabled) "Engineering on" else "Engineering off")
                        if (admission.principalId.startsWith("tailnet:")) {
                            Switch(admission.enabled, onCheckedChange = { _ -> model.toggleDirect(it) }, enabled = !busy)
                        }
                    } else {
                        GatewayControlSettingsContent(it, states[it.uid] ?: GatewayControlState(targetName = it.name), busy, model, initialPairingLink)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun GatewayControlSettingsContent(profile: PlatformV2, state: GatewayControlState, busy: Boolean, model: GatewayControlViewModel, initialPairingLink: String? = null) {
    var pairLink by remember(profile.uid, initialPairingLink) { mutableStateOf(initialPairingLink.orEmpty()) }
    var showPairing by remember(profile.uid, initialPairingLink) { mutableStateOf(initialPairingLink != null && !state.writable) }
    var engineeringConfirmation by remember(profile.uid) { mutableStateOf(false) }
    var revokeConfirmation by remember(profile.uid) { mutableStateOf(false) }
    var draft by remember(profile.uid) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var draftRevision by remember(profile.uid) { mutableStateOf<Long?>(null) }
    val snapshot = state.snapshot
    LaunchedEffect(snapshot?.revision, state.phase) {
        if (state.phase == GatewayControlPhase.LIVE && draft.isNotEmpty() && draft.all { (field, value) -> snapshot?.desired?.get(field) == value }) {
            draft = emptyMap()
            draftRevision = null
        }
    }
    LaunchedEffect(state.writable) {
        if (state.writable) {
            showPairing = false
            pairLink = ""
        }
    }
    val live = state.writable && !busy
    val label = when (state.phase) {
        GatewayControlPhase.UNPAIRED -> "Connect to Gateway"
        GatewayControlPhase.PAIRING -> "Waiting for PC confirmation"
        GatewayControlPhase.LIVE -> when {
            snapshot?.pending?.isNotEmpty() == true -> "Requested settings need attention"
            snapshot?.hostRequests?.any { it.status == "waiting_pc" } == true -> "Waiting for PC review"
            else -> "Applied"
        }
        GatewayControlPhase.SAVING -> "Applying…"
        GatewayControlPhase.RECONCILING -> "Checking the original change"
        GatewayControlPhase.STALE -> "Offline · last confirmed values · read only"
        GatewayControlPhase.CONFLICT -> "Changed elsewhere"
        GatewayControlPhase.REVOKED -> "Access revoked"
    }
    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

    state.targetEndpoint?.let { Text("Connected PC · $it", style = MaterialTheme.typography.labelSmall) }
    state.receivedAtMillis?.let { at -> Text("Last confirmed ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))}", style = MaterialTheme.typography.labelSmall) }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    state.deviceFingerprint?.let { fingerprint ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("This phone's key fingerprint", style = MaterialTheme.typography.titleSmall)
                Text(fingerprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gateway_phone_key_fingerprint"))
                Text("Approve on the PC only when its phone key fingerprint matches this one.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showPairing || state.phase in setOf(GatewayControlPhase.UNPAIRED, GatewayControlPhase.REVOKED, GatewayControlPhase.PAIRING)) {
        Text("Open Connect phone on your PC and scan or paste its connection link.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            pairLink,
            { if (it.length <= 4096) pairLink = it },
            label = { Text("Connection link") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("gateway_pairing_link"),
            enabled = !busy
        )
        val preview = remember(pairLink) { runCatching { GatewayPairingLink.parse(pairLink, System.currentTimeMillis() / 1000) }.getOrNull() }
        preview?.let {
            Text(it.endpoint, style = MaterialTheme.typography.labelSmall)
        }
        Row {
            OutlinedButton(onClick = {
                val submitted = pairLink
                pairLink = ""
                showPairing = false
                model.pair(profile, submitted)
            }, enabled = preview != null && !busy) { Text("Connect") }
            if (state.phase == GatewayControlPhase.PAIRING) TextButton(onClick = { model.finishPairing(profile) }, enabled = !busy) { Text("Check confirmation") }
        }
    }
    if (state.phase == GatewayControlPhase.STALE && !showPairing) {
        TextButton(onClick = { showPairing = true }) { Text("Reconnect") }
    }
    if (snapshot != null && state.phase !in setOf(GatewayControlPhase.UNPAIRED, GatewayControlPhase.PAIRING)) {
        Text("${if (snapshot.role == "owner") "Paired owner" else "Viewer"} · Revision ${snapshot.revision} · ${snapshot.installationId.takeLast(12)}", style = MaterialTheme.typography.labelSmall)
        snapshot.updates?.let { update ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Gateway updates", style = MaterialTheme.typography.titleMedium)
                    Text("Installed ${update.installed} · ${update.state.replace('-', ' ')}")
                    update.available?.let { Text("Available $it", style = MaterialTheme.typography.bodySmall) }
                    if ("update_mode" in snapshot.writableFields) {
                        val ready = live && update.state !in setOf("checking", "downloading", "waiting-for-idle", "validating")
                        OutlinedButton(onClick = { model.updateGateway(profile) }, enabled = ready) { Text("Check for updates") }
                        if (update.available != null && update.state == "available") {
                            OutlinedButton(onClick = { model.updateGateway(profile, update.available) }, enabled = ready) { Text("Install ${update.available} when idle") }
                        }
                    }
                }
            }
        }
        val desiredEngineering = snapshot.desired["engineering_enabled"] == JsonPrimitive(true)
        val effectiveEngineering = snapshot.effective["engineering_enabled"] == JsonPrimitive(true)
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Engineering", style = MaterialTheme.typography.titleMedium)
                        Text("Run jobs for your connected repositories.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(desiredEngineering, onCheckedChange = { on ->
                        if (on && !state.engineeringBindingConfirmed) {
                            engineeringConfirmation = true
                        } else {
                            model.apply(profile, snapshot.revision, JsonObject(mapOf("engineering_enabled" to JsonPrimitive(on))))
                        }
                    }, enabled = live && "engineering_enabled" in snapshot.writableFields, modifier = Modifier.testTag("gateway_owner_engineering"))
                }
                Text("Requested: ${if (desiredEngineering) "On" else "Off"} · Effective: ${if (effectiveEngineering) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
                if (desiredEngineering && !state.engineeringBindingConfirmed && live) {
                    TextButton(onClick = { engineeringConfirmation = true }) { Text("Apply Engineering to this phone") }
                }
            }
        }
        var advanced by remember(profile.uid) { mutableStateOf(false) }
        if (GatewayControlField.entries.any { it != GatewayControlField.ENGINEERING && it.wire in snapshot.writableFields }) {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
        }
        GatewayControlField.entries.filter { advanced && it != GatewayControlField.ENGINEERING && it.wire in snapshot.writableFields && it.wire in snapshot.desired }.forEach { field ->
            val value = draft[field.wire] ?: snapshot.desired.getValue(field.wire)
            val choices = when (field) {
                GatewayControlField.VALIDATION_PROFILE -> snapshot.validationProfiles
                GatewayControlField.BACKEND -> snapshot.approvedBackends.associateWith { it }
                else -> field.choices.associateWith { it.replaceFirstChar(Char::uppercase) }
            }
            GatewayControlFieldEditor(field, value, snapshot.effective[field.wire], live && field.wire in snapshot.writableFields, choices) { newValue ->
                if (draftRevision == null) draftRevision = snapshot.revision
                draft = draft + (field.wire to newValue)
            }
        }
        snapshot.pending.forEach { pending ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(GatewayControlField.fromWire(pending.field)?.label ?: "Gateway prerequisite", style = MaterialTheme.typography.titleSmall)
                    Text(pending.reason, style = MaterialTheme.typography.bodySmall)
                    if (pending.blockingJobs.isNotEmpty()) Text("Waiting for jobs: ${pending.blockingJobs.joinToString()}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (draft.isNotEmpty()) {
            Text("Local draft · ${draft.size} change(s). Changes are sent only when you apply them.", style = MaterialTheme.typography.bodySmall)
            if (draftRevision != snapshot.revision || state.phase == GatewayControlPhase.CONFLICT) {
                Text("The PC has a newer revision. Compare requested and effective values before applying your draft.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    draftRevision = snapshot.revision
                    model.refresh(profile)
                }, enabled = !busy) { Text("Keep draft for this revision") }
            }
            Row {
                OutlinedButton(onClick = {
                    val changes = JsonObject(draft)
                    val revision = requireNotNull(draftRevision)
                    model.apply(profile, revision, changes)
                }, enabled = live && draftRevision == snapshot.revision && runCatching { snapshot.validateChanges(JsonObject(draft)) }.isSuccess) { Text("Apply settings") }
                TextButton(onClick = {
                    draft = emptyMap()
                    draftRevision = null
                }, enabled = !busy) { Text("Discard draft") }
            }
        }
        if ("host_request" in snapshot.writableFields) {
            var requestMenu by remember(profile.uid) { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { requestMenu = true }, enabled = live && snapshot.hostRequests.none { it.status == "waiting_pc" }) { Text("Request PC review") }
                DropdownMenu(requestMenu, { requestMenu = false }) {
                    GatewayControlField.HOST_REQUEST.choices.forEach { request ->
                        DropdownMenuItem(text = { Text(request) }, onClick = {
                            requestMenu = false
                            model.apply(profile, snapshot.revision, JsonObject(mapOf("host_request" to JsonPrimitive(request))))
                        })
                    }
                }
            }
            Text("Sends a review note to the PC. Network access, setup and restart decisions are made there.", style = MaterialTheme.typography.bodySmall)
        }
        snapshot.hostRequests.forEach { request ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(request.request, style = MaterialTheme.typography.titleSmall)
                    Text(if (request.status == "waiting_pc") "Waiting for local PC review" else "Marked reviewed on the PC", style = MaterialTheme.typography.bodySmall)
                    Text("Requested ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date((request.createdAt * 1000).toLong()))}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (snapshot.role == "owner") TextButton(onClick = { revokeConfirmation = true }, enabled = !busy && state.phase !in setOf(GatewayControlPhase.REVOKED, GatewayControlPhase.RECONCILING)) { Text("Revoke this phone") }
    }
    state.pendingCommand?.let { pending ->
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Original change awaiting confirmation", style = MaterialTheme.typography.titleSmall)
                Text("PC: ${state.targetEndpoint ?: profile.name} · installation ${pending.installationId.takeLast(12)}", style = MaterialTheme.typography.bodySmall)
                pending.changes.forEach { (field, value) ->
                    Text("${GatewayControlField.fromWire(field)?.label ?: field}: ${(value as? JsonPrimitive)?.content.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Checks whether this change already reached the PC before retrying it. Refresh alone never resends it.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { model.retryPending(profile, state) }, enabled = !busy && state.phase == GatewayControlPhase.RECONCILING && snapshot != null && state.targetEndpoint != null) { Text("Retry original change") }
            }
        }
    }
    OutlinedButton(onClick = { model.refresh(profile) }, enabled = !busy) { Text(if (busy) "Checking…" else "Refresh confirmed state") }
    if (engineeringConfirmation && snapshot != null) {
        AlertDialog(
            onDismissRequest = { engineeringConfirmation = false },
            title = { Text("Apply Engineering to ${profile.name.take(80)}?") },
            text = { Text("This links new engineering work to your paired owner on ${state.targetEndpoint ?: profile.name} (installation ${snapshot.installationId.takeLast(12)}). Your inference model stays selected. Repository permissions, host prerequisites and job approvals still apply. Other unpaired devices receive no grant.") },
            confirmButton = {
                TextButton(onClick = {
                    engineeringConfirmation = false
                    model.apply(profile, snapshot.revision, JsonObject(mapOf("engineering_enabled" to JsonPrimitive(true))), confirmEngineering = true)
                }, enabled = live) { Text("Apply Engineering") }
            },
            dismissButton = { TextButton(onClick = { engineeringConfirmation = false }) { Text("Cancel") } }
        )
    }
    if (revokeConfirmation) {
        AlertDialog(
            onDismissRequest = { revokeConfirmation = false },
            title = { Text("Revoke control access on this phone?") },
            text = { Text("${profile.name.take(120)} will reject this phone's paired controls. Saved jobs and conversations stay on the PC. Pair again there to restore access.") },
            confirmButton = {
                TextButton(onClick = {
                    revokeConfirmation = false
                    model.revoke(profile)
                }) { Text("Revoke access") }
            },
            dismissButton = { TextButton(onClick = { revokeConfirmation = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GatewayControlFieldEditor(field: GatewayControlField, requested: JsonElement, effective: JsonElement?, enabled: Boolean, choices: Map<String, String>, onChange: (JsonElement) -> Unit) {
    val value = (requested as? JsonPrimitive)?.content.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            field.boolean -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, Modifier.weight(1f))
                Switch((requested as? JsonPrimitive)?.booleanOrNull == true, { onChange(JsonPrimitive(it)) }, enabled = enabled)
            }
            field.choices.isNotEmpty() || field in setOf(GatewayControlField.VALIDATION_PROFILE, GatewayControlField.BACKEND) -> {
                var expanded by remember(field) { mutableStateOf(false) }
                Text(field.label, style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty()) { Text(choices[value] ?: if (field == GatewayControlField.BACKEND) value else value.replaceFirstChar(Char::uppercase)) }
                    DropdownMenu(expanded, { expanded = false }) {
                        choices.forEach { (choice, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                expanded = false
                                onChange(JsonPrimitive(choice))
                            })
                        }
                    }
                }
                if (field == GatewayControlField.VALIDATION_PROFILE) {
                    Text(if (choices.isEmpty()) "Approve a repository validation profile on the PC first." else "Only profiles approved on this PC for your repositories are available.", style = MaterialTheme.typography.bodySmall)
                    if (value.length == 64) Text("Profile hash: $value", style = MaterialTheme.typography.labelSmall)
                }
                if (field == GatewayControlField.BACKEND) Text("Choose a backend approved on the PC. The PC checks its connection before activating it.", style = MaterialTheme.typography.bodySmall)
            }
            else -> OutlinedTextField(
                value,
                { text ->
                    if (field.minimum != null) {
                        if (text.isEmpty()) {
                            onChange(JsonPrimitive(""))
                        } else {
                            text.toIntOrNull()?.let { onChange(JsonPrimitive(it)) }
                        }
                    } else if (text.length <= 512) {
                        onChange(JsonPrimitive(text))
                    }
                },
                label = { Text(field.label) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { if (field.minimum != null) Text("${field.minimum}–${field.maximum}") else Text("Only a backend or model already approved on this PC can activate.") }
            )
        }
        val effectiveValue = (effective as? JsonPrimitive)?.content
        Text("Effective: ${effectiveValue?.let { choices[it] } ?: effectiveValue ?: "Not confirmed"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
