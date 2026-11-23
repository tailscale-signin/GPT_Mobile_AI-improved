package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.privacy.OpenRouterPrivacySetup
import dev.chungjungsoo.gptmobile.data.privacy.ProviderApprovalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProviderVerificationSetup(
    source: ProviderApprovalSource,
    setup: OpenRouterPrivacySetup,
    profiles: List<PlatformV2>
) {
    var selectedUid by remember { mutableStateOf<String?>(null) }
    val profile = profiles.firstOrNull { it.uid == selectedUid } ?: profiles.firstOrNull()
    val currentProfile by rememberUpdatedState(profile)
    var management by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<OpenRouterPrivacySetup.Preview?>(null) }
    var pluginsConfirmed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var advancedBusy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var savedStatus by remember(setup, profile, refresh) { mutableStateOf<OpenRouterPrivacySetup.SavedStatus?>(null) }
    var savedStatusFailed by remember(setup, profile, refresh) { mutableStateOf(false) }
    val observedStatus = savedStatus
    val summary = observedStatus?.summary
    val scope = rememberCoroutineScope()
    val handler = LocalUriHandler.current
    val anyBusy = busy || advancedBusy

    fun clearPreview() {
        preview?.let(setup::discard)
        preview = null
        pluginsConfirmed = false
    }
    fun changed() {
        clearPreview()
        refresh++
    }
    LaunchedEffect(setup, profile, refresh) {
        try {
            savedStatus = setup.savedStatus(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            savedStatusFailed = true
        }
    }
    LaunchedEffect(profile, setup) {
        operation?.cancel()
        clearPreview()
        status = ""
        failure = false
    }
    DisposableEffect(setup) {
        onDispose {
            operation?.cancel()
            preview?.let(setup::discard)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("OpenRouter privacy setup", style = MaterialTheme.typography.titleMedium)
        if (observedStatus == null) {
            if (savedStatusFailed) {
                Text(
                    "Saved account verification could not be read. Privacy Mode and profile selection remain available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("privacy_setup_read_error")
                )
                TextButton(onClick = { refresh++ }, modifier = Modifier.testTag("privacy_setup_retry_read")) {
                    Text("Retry saved setup")
                }
            } else {
                Text("Reading saved account verification…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("privacy_setup_loading"))
            }
        } else if (observedStatus.managed) {
            Text("Your organization manages account verification. Its settings take priority. Contact your administrator if a profile remains unavailable.", style = MaterialTheme.typography.bodySmall)
        } else if (profile == null) {
            Text("Add and enable an OpenRouter profile with its API key to set up cloud privacy. On-device models and your private Gateway do not need this account setup.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Choose a saved account, check its settings, then apply the recommended protection. Setup is shared by profiles using the same saved key and endpoint.", style = MaterialTheme.typography.bodySmall)
            if (profiles.size > 1) {
                profiles.forEach { candidate ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = candidate.uid == profile.uid,
                            enabled = !anyBusy,
                            role = Role.RadioButton,
                            onClick = {
                                if (!busy && !advancedBusy) {
                                    clearPreview()
                                    selectedUid = candidate.uid
                                }
                            }
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = candidate.uid == profile.uid, onClick = null, enabled = !anyBusy)
                        Text(candidate.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
            }
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("privacy_setup_saved_status")) }
            OutlinedTextField(
                value = management,
                onValueChange = {
                    if (!busy && !advancedBusy) {
                        clearPreview()
                        status = ""
                        management = it.take(4096)
                    }
                },
                enabled = !anyBusy,
                label = { Text("OpenRouter management key") },
                supportingText = { Text("Use a read/write management key for this workspace. Leave blank to reuse your securely saved key.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("privacy_setup_management_key")
            )
            TextButton(enabled = !anyBusy, onClick = { handler.openUri("https://openrouter.ai/settings/management-keys") }) {
                Text("Get a management key")
            }
            Button(enabled = !anyBusy, modifier = Modifier.testTag("privacy_setup_check"), onClick = {
                if (!busy && !advancedBusy) {
                    val selected = profile
                    val key = management
                    clearPreview()
                    busy = true
                    failure = false
                    status = "Checking account settings…"
                    operation = scope.launch {
                        var inspected: OpenRouterPrivacySetup.Preview? = null
                        try {
                            inspected = setup.inspect(selected, key)
                            ensureActive()
                            if (currentProfile != selected) return@launch
                            preview = inspected
                            status = "Account checked. Review the settings below."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            status = (error as? OpenRouterPrivacySetup.SetupException)?.userMessage
                                ?: "Could not check this account. Check the management key and connection, then try again."
                            failure = true
                        } finally {
                            inspected?.takeUnless { it === preview }?.let(setup::discard)
                            busy = false
                        }
                    }
                }
            }) { Text(if (busy && preview == null) "Checking…" else "Check my account") }
            preview?.let { checked ->
                Text("Workspace: ${checked.workspaceName}", style = MaterialTheme.typography.titleSmall)
                PrivacySetupCheck("Prompt logging", checked.promptLoggingOff)
                PrivacySetupCheck("Product-improvement collection", checked.dataCollectionOff)
                PrivacySetupCheck("Broadcast exports", checked.broadcastOff)
                Text(
                    "Strict Zero Data Retention: " + if (checked.guardrailReady) "Already configured" else "Needs setup or a policy review",
                    style = MaterialTheme.typography.bodySmall
                )
                if (checked.policyReviewRequired) {
                    Text("Your existing or inherited policy needs a review in OpenRouter Guardrails. Require ZDR for every model group and turn off both Safety Retention options, then check this account again. The app can apply the three account controls below while preserving that policy.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !anyBusy, onClick = { handler.openUri("https://openrouter.ai/docs/guides/features/guardrails/policies") }) {
                        Text("Review policy instructions")
                    }
                }
                Text("Apply turns off logging, product-improvement collection and Broadcast in this workspace, affecting its other keys and apps. It also checks strict Zero Data Retention and configures eligible keys automatically. Existing shared policies are preserved; if one needs a change, setup will explain what to review in OpenRouter.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !anyBusy, onClick = { handler.openUri("https://openrouter.ai/settings/plugins") }) {
                    Text("Review default plugins in OpenRouter")
                }
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = pluginsConfirmed,
                        onCheckedChange = { if (!busy && !advancedBusy) pluginsConfirmed = it },
                        enabled = !anyBusy,
                        modifier = Modifier.testTag("privacy_setup_plugins_confirmed")
                    )
                    Text(
                        "I reviewed this workspace in OpenRouter and turned off all default plugins, including locked defaults.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(top = 10.dp)
                    )
                }
                Text("OpenRouter does not expose this plugin check through its documented API. Your confirmation is saved on this device for up to 24 hours; the app checks the other settings automatically.", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !anyBusy && pluginsConfirmed,
                    modifier = Modifier.testTag("privacy_setup_apply"),
                    onClick = {
                        if (!busy && !advancedBusy && pluginsConfirmed) {
                            val selected = profile
                            busy = true
                            failure = false
                            status = "Applying and checking privacy settings…"
                            operation = scope.launch {
                                try {
                                    val result = setup.apply(selected, checked, pluginsConfirmed = true)
                                    ensureActive()
                                    if (currentProfile != selected) return@launch
                                    status = result
                                    changed()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    status = (error as? OpenRouterPrivacySetup.SetupException)?.userMessage
                                        ?: "Setup could not be completed. Check this account again to review its current settings."
                                    failure = true
                                    clearPreview()
                                } finally {
                                    management = ""
                                    busy = false
                                }
                            }
                        }
                    }
                ) { Text(if (busy) "Applying…" else "Apply recommended settings") }
            }
        }
        if (anyBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("privacy_setup_status")
            )
        }
        AdvancedProviderVerificationSetup(
            source = source,
            blocked = busy,
            canStart = { !busy && !advancedBusy },
            onBusy = { advancedBusy = it },
            onChanged = ::changed
        )
    }
}

@Composable
private fun PrivacySetupCheck(label: String, disabled: Boolean) {
    Text("$label: " + if (disabled) "Off · checked" else "Will be turned off", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AdvancedProviderVerificationSetup(
    source: ProviderApprovalSource,
    blocked: Boolean,
    canStart: () -> Boolean,
    onBusy: (Boolean) -> Unit,
    onChanged: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var issuer by remember { mutableStateOf("") }
    var document by remember { mutableStateOf("") }
    var management by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(success: String, failure: String = "Could not verify or save these settings. Check the issuer, signature and file format.", task: suspend () -> Unit) {
        if (blocked || busy || !canStart()) return
        busy = true
        onBusy(true)
        onChanged()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { task() }
                status = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = failure
            } finally {
                busy = false
                management = ""
                onBusy(false)
                onChanged()
            }
        }
    }
    TextButton(enabled = !busy && !blocked, onClick = { expanded = !expanded }, modifier = Modifier.testTag("privacy_setup_advanced")) {
        Text(if (expanded) "Close advanced setup" else "Advanced / organization setup")
    }
    if (!expanded) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Use this for an administrator's signed verification or another cloud provider's account approval. Managed device configuration always takes priority.", style = MaterialTheme.typography.bodySmall)
        Text("Obtain the administrator’s P-256 public key through a trusted channel. Trusting a new issuer revokes the previous file.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = issuer, onValueChange = { issuer = it.take(2048) }, enabled = !busy && !blocked, label = { Text("Administrator public key (base64 DER)") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && !blocked && issuer.isNotBlank(), onClick = {
            val value = issuer
            save("Issuer saved. Import its signed account verification.") { source.trustIssuer(value) }
        }) { Text("Trust administrator key") }
        OutlinedTextField(value = document, onValueChange = { document = it.take(131072) }, enabled = !busy && !blocked, label = { Text("Signed verification JSON") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && !blocked && document.isNotBlank(), onClick = {
            val value = document
            save("Signature verified. Profiles will be checked when you return to New Chat.") { source.install(value) }
        }) { Text("Verify and import") }
        Text("OpenRouter uses a management key to check the assigned privacy guardrail. Keep the inference key in your AI profile.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = management, onValueChange = { management = it.take(4096) }, enabled = !busy && !blocked, label = { Text("OpenRouter management key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && !blocked && management.isNotBlank(), onClick = {
            val value = management
            save("Key saved for guardrail verification.", "Could not save the management key. Check its format and the device’s secure storage.") { source.setManagementKey(value) }
        }) { Text("Save guardrail verification key") }
        Text("Automatic tightening requires permission in the signed account evidence. Other keys assigned to the same guardrail may also be affected.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy && !blocked, onClick = {
            save("Strict settings may be applied to guardrails authorized by your administrator.") { source.allowGuardrailUpdates() }
        }) { Text("Authorize stricter guardrail settings") }
        TextButton(enabled = !busy && !blocked, onClick = {
            save("Personal verification settings removed.") {
                source.forget()
                source.setManagementKey("")
            }
        }) { Text("Remove personal verification settings") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
