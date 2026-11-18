package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.chungjungsoo.gptmobile.data.privacy.ProviderApprovalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProviderVerificationSetup(source: ProviderApprovalSource) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Close account setup" else "Set up account verification") }
    if (!expanded) return
    var issuer by remember { mutableStateOf("") }
    var document by remember { mutableStateOf("") }
    var management by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(success: String, task: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { task() }
                status = success
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status = "Could not verify or save these settings. Check the issuer, signature and file format."
            } finally {
                busy = false
                management = ""
            }
        }
    }
    Column {
        Text("Obtain the account administrator’s P-256 public key through a trusted channel. Trusting a new issuer revokes the previous file. Managed devices use their organization’s configuration.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = issuer, onValueChange = { issuer = it.take(2048) }, label = { Text("Administrator public key (base64 DER)") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && issuer.isNotBlank(), onClick = { save("Issuer saved. Import its signed account verification.") { source.trustIssuer(issuer) } }) { Text("Trust administrator key") }
        OutlinedTextField(value = document, onValueChange = { document = it.take(131072) }, label = { Text("Signed verification JSON") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && document.isNotBlank(), onClick = { save("Signature verified. Routes will be rechecked in New Chat.") { source.install(document) } }) { Text("Verify and import") }
        OutlinedTextField(value = management, onValueChange = { management = it.take(4096) }, label = { Text("OpenRouter management key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && management.isNotBlank(), onClick = { save("Key saved for read-only guardrail and assignment checks.") { source.setManagementKey(management) } }) { Text("Save guardrail verification key") }
        TextButton(enabled = !busy, onClick = { save("Strict settings may be applied to guardrails authorized by your administrator.") { source.allowGuardrailUpdates() } }) { Text("Authorize stricter OpenRouter guardrail settings") }
        TextButton(enabled = !busy, onClick = {
            save("Personal verification settings removed.") {
                source.forget()
                source.setManagementKey("")
            }
        }) { Text("Remove personal verification settings") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
