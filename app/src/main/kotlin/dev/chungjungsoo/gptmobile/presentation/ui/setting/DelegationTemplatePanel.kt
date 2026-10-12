package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.chungjungsoo.gptmobile.data.delegation.DelegationTemplate
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings

@Composable
internal fun DelegationTemplatePanel(config: ModelDelegationSettings, onChange: ((ModelDelegationSettings) -> ModelDelegationSettings) -> Unit) {
    var candidate by remember { mutableStateOf<DelegationTemplate?>(null) }
    var transfer by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    SettingsPanel("Task templates") {
        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_templates_change_tuning_and_requested_review__a57aa))
        DelegationTemplate.Role.entries.forEach { role ->
            TextButton(onClick = { candidate = DelegationTemplate(role = role) }) { Text(role.name.lowercase().replace('_', ' ')) }
        }
        TextButton(onClick = { transfer = runCatching { DelegationTemplate.encode(DelegationTemplate.from(config)) }.getOrElse { "" } }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_export_import_template_json_71174)) }
    }
    transfer?.let { text ->
        AlertDialog(onDismissRequest = { transfer = null }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_portable_template_56ab1)) }, text = {
            Column {
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_copy_this_json_or_paste_a_template_to_preview_d61d5))
                OutlinedTextField(text, {
                    transfer = it.take(8193)
                    failure = null
                }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_schema_v1_json_ca14d)) })
                failure?.let { Text(it) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                runCatching { DelegationTemplate.decode(text) }.onSuccess {
                    candidate = it
                    transfer = null
                }.onFailure { failure = it.message ?: "Invalid template" }
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_validate_and_preview_921b2)) }
        }, dismissButton = { TextButton(onClick = { transfer = null }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_close_7d9eb)) } })
    }
    candidate?.let { template ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_apply_template_tuning_11b26)) }, text = {
            Text("${template.description}\n\nInput ${config.maxInputTokensPerDelegate} → ${template.inputTokens} tokens\nOutput ${config.maxOutputTokens} → ${template.outputTokens} tokens\nCalls ${config.maxLocalModelCalls} → ${template.calls}\nConcurrency ${config.maxConcurrentDelegates} → ${template.concurrency}\nReview requested: ${template.reviewRequested}\n\nThe next response will use this template. Only authorized read-only tools can be used by its helper.")
        }, confirmButton = {
            TextButton(onClick = {
                onChange { template.applyTo(it) }
                candidate = null
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_apply_31e39)) }
        }, dismissButton = { TextButton(onClick = { candidate = null }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_cancel_19766)) } })
    }
}
