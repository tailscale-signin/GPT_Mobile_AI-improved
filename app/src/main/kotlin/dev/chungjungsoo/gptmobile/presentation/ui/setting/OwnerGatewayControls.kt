package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.pairing.OwnerCredential
import dev.chungjungsoo.gptmobile.data.pairing.OwnerGateway
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@Composable
internal fun OwnerGatewayControls(entry: OwnerCredential, name: String, compact: Boolean = false, profileUid: String? = null, appEngineering: Boolean? = null) = key(entry) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val binding = remember(entry, profileUid) { context.getSharedPreferences("paired-engineering", android.content.Context.MODE_PRIVATE) }
    val bindingKey = entry.alias + ":" + profileUid
    var firstPrompt by remember(entry, profileUid) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }
    var observed by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var forgetOnly by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    suspend fun read(retry: Boolean = false) {
        lock.withLock {
            try {
                observed = OwnerGateway.settings(entry, retry = retry)
                live = true
                error = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                live = false
                if (observed == null) observed = OwnerGateway.lastConfirmed(entry)
                error = failure.message ?: "Gateway is offline."
            }
        }
    }
    LaunchedEffect(entry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                read()
                delay(5000)
            }
        }
    }
    fun change(field: String, value: Any, confirmed: () -> Unit = {}) {
        val current = observed ?: return
        if (!live || writing || error.isNotEmpty() || entry.role != "owner") return
        writing = true
        scope.launch {
            try {
                lock.withLock {
                    val payload = JSONObject().put("command_id", UUID.randomUUID().toString().replace("-", ""))
                        .put("device", entry.device).put("installation", current.getString("installation")).put("revision", current.getString("revision"))
                        .put("changes", JSONObject().put(field, value))
                    observed = OwnerGateway.settings(entry, payload)
                    confirmed()
                    error = ""
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                live = false
                error = failure.message ?: "Change unconfirmed. Refresh Gateway settings."
            } finally {
                writing = false
            }
        }
    }
    val desired = observed?.optJSONObject("desired")
    val actual = observed?.optJSONObject("effective")
    val receipt = observed?.optString("command_state").orEmpty()
    val outstanding = receipt in setOf("queued", "saved", "unconfirmed")
    val editable = live && !outstanding && desired != null && entry.role == "owner" && !writing && error.isEmpty()
    val sequence = observed?.optLong("sequence")
    var attempted by remember(bindingKey, appEngineering) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(bindingKey, appEngineering, editable, sequence) {
        if (profileUid == null || appEngineering == null || !editable) return@LaunchedEffect
        if (!binding.contains(bindingKey)) {
            if (appEngineering && actual?.optBoolean("engineering") != true) {
                firstPrompt = true
            } else {
                binding.edit().putBoolean(bindingKey, appEngineering).apply()
            }
        } else if (binding.getBoolean(bindingKey, false) != appEngineering && !attempted) {
            attempted = true
            if (actual?.optBoolean("engineering") == appEngineering) {
                binding.edit().putBoolean(bindingKey, appEngineering).apply()
            } else {
                change("engineering", appEngineering) { binding.edit().putBoolean(bindingKey, appEngineering).apply() }
            }
        }
    }
    if (firstPrompt && appEngineering == true && editable) {
        fun dismiss() {
            firstPrompt = false
            binding.edit().putBoolean(bindingKey, true).apply()
        }
        AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text("Apply Engineering to $name?") },
            text = { Text("Engineering is on for this app profile. Allow this phone to submit new engineering jobs to this Gateway?") },
            confirmButton = {
                TextButton(onClick = {
                    firstPrompt = false
                    change("engineering", true) { dismiss() }
                }) { Text("Apply to this PC") }
            },
            dismissButton = { TextButton(onClick = { dismiss() }) { Text("Keep current setting") } }
        )
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Engineering")
                    Text("Allow new jobs from this phone on the selected PC.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = actual?.optBoolean("engineering") == true, onCheckedChange = { change("engineering", it) }, enabled = editable)
            }
            Text(
                when {
                    error.isNotEmpty() && observed != null -> "Last confirmed ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(observed!!.optLong("observed_at")))} · $error"
                    error.isNotEmpty() -> error
                    observed == null -> "Reading Gateway settings…"
                    writing -> "Applying change…"
                    entry.role != "owner" -> "Viewer · read-only access"
                    receipt == "backend_unavailable" -> "Backend unavailable. The previous model route is still active."
                    receipt == "conflict" -> "A Windows edit superseded this change."
                    receipt == "unconfirmed" -> "The Gateway has no receipt for your last change."
                    observed?.optJSONArray("pending_fields")?.length() != 0 -> "Saved · waiting for the runtime to apply changes"
                    else -> "Applied on $name"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (!compact || error.isNotEmpty()) TextButton(onClick = { scope.launch { read() } }, enabled = !writing) { Text("Refresh settings") }
            if (receipt == "unconfirmed") TextButton(onClick = { scope.launch { read(retry = true) } }) { Text("Retry original change") }
            OutlinedButton(onClick = { details = true }) { Text("Gateway settings") }
        }
    }
    if (details) {
        AlertDialog(onDismissRequest = { details = false }, title = { Text(name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings apply to this PC. Active jobs keep their original execution settings.")
                listOf("start_at_login" to "Start at sign-in", "public_repository_maps" to "Public repository maps", "debug_mode" to "Diagnostic recording").forEach { (field, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        Switch(desired?.optBoolean(field) == true, { change(field, it) }, enabled = editable)
                    }
                }
                for ((field, label) in listOf("workers" to "Worker limit", "active_chats" to "Chat limit", "update_mode" to "Update policy", "backend" to "Approved backend", "batch_mode" to "Batch mode", "validation_profile" to "Repository validation")) {
                    val choices = when (field) {
                        "workers" -> (1..minOf(16, desired?.optInt("active_chats", 1) ?: 1)).map(Int::toString)
                        "active_chats" -> ((desired?.optInt("workers", 1) ?: 1)..64).map(Int::toString)
                        "validation_profile" -> observed?.optJSONObject("validation_profiles")?.keys()?.asSequence()?.toList().orEmpty()
                        "update_mode" -> listOf("off", "notify", "auto")
                        else -> (if (field == "backend") observed?.optJSONArray("backends") else observed?.optJSONArray("batch_modes"))?.let { list -> (0 until list.length()).map { list.getString(it) } }.orEmpty()
                    }
                    fun display(value: String): String = if (field == "validation_profile") {
                        observed?.optJSONObject("validation_profiles")?.optString(value)?.takeIf { it.isNotEmpty() } ?: if (value == "disabled") "Disabled" else "Host selection"
                    } else {
                        value
                    }
                    var menu by remember { mutableStateOf(false) }
                    Column {
                        OutlinedButton(enabled = editable && choices.isNotEmpty(), onClick = { menu = true }) { Text("$label · ${display(desired?.optString(field) ?: "Unknown")}") }
                        DropdownMenu(menu, { menu = false }) {
                            choices.forEach { option ->
                                DropdownMenuItem(text = { Text(display(option)) }, onClick = {
                                    menu = false
                                    change(field, if (field in setOf("workers", "active_chats")) option.toInt() else option)
                                })
                            }
                        }
                    }
                    if (actual?.opt(field) != desired?.opt(field)) Text("Currently active: ${display(actual?.optString(field) ?: "Unknown")}", style = MaterialTheme.typography.bodySmall)
                }
                observed?.optJSONObject("last_change")?.takeIf { it.has("sequence") }?.let {
                    Text("Change ${it.optLong("sequence")} · ${it.optString("field")} · ${it.optString("source")} · ${it.optString("state")}")
                }
                if (actual?.optBoolean("engineering") == true && observed?.optBoolean("engineering_ready", true) == false) {
                    val checks = observed?.optJSONObject("engineering_checks")
                    Text("Engineering setup required · GitHub ${checks?.optString("host_github_connection")} · Worker ${checks?.optString("worker")} · Journal ${checks?.optString("journal")}")
                }
                observed?.optJSONArray("jobs")?.let { jobs ->
                    for (index in 0 until jobs.length()) {
                        jobs.optJSONObject(index)?.let { job -> Text("${job.optString("id")} · ${job.optString("state")}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                var requestMenu by remember { mutableStateOf(false) }
                Column {
                    OutlinedButton(onClick = { requestMenu = true }, enabled = editable) { Text("Request a Windows action") }
                    DropdownMenu(requestMenu, { requestMenu = false }) {
                        listOf("Review phone network access", "Open engineering setup", "Review pending restart").forEach { action ->
                            DropdownMenuItem(text = { Text(action) }, onClick = {
                                requestMenu = false
                                change("host_request", action)
                            })
                        }
                    }
                }
                TextButton(onClick = {
                    forgetOnly = false
                    confirmRemove = true
                }, enabled = !writing) { Text("Remove phone access") }
                if (error.isNotEmpty()) {
                    TextButton(onClick = {
                        forgetOnly = true
                        confirmRemove = true
                    }) { Text("Forget offline pairing") }
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } })
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (forgetOnly) "Forget this pairing?" else "Remove phone access?") },
            text = { Text(if (forgetOnly) "Delete this phone’s private key. The PC will keep its grant until you revoke it on Windows." else "Revoke this device on the PC. You can pair it again with a new invitation.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    details = false
                    scope.launch {
                        try {
                            OwnerGateway.remove(entry, !forgetOnly)
                            observed = null
                            error = "Phone access removed."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Revocation unconfirmed."
                        }
                    }
                }) { Text(if (forgetOnly) "Forget pairing" else "Revoke access") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}
