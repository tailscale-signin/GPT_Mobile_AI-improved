package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.DelegationPlanStep
import dev.chungjungsoo.gptmobile.data.delegation.DelegationReadinessInspector
import dev.chungjungsoo.gptmobile.data.delegation.DelegationTaskPlan
import dev.chungjungsoo.gptmobile.data.delegation.DelegationTemplate
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DelegationPlanEditor(config: ModelDelegationSettings, profiles: List<PlatformV2>, onChange: ((ModelDelegationSettings) -> ModelDelegationSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var raw by rememberSaveable { mutableStateOf(Json.encodeToString(config.taskPlan)) }
    var base by rememberSaveable { mutableStateOf(Json.encodeToString(config.taskPlan)) }
    var error by remember { mutableStateOf<String?>(null) }
    var inspectedUid by rememberSaveable { mutableStateOf(config.targetProfileUid) }
    var inspect by remember { mutableStateOf<PlatformV2?>(null) }
    SettingsPanel(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d56cca8f80)) {
        Text(if (config.taskPlan.steps.isEmpty()) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fd062b0f76) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_911fc5be64, (config.taskPlan.steps.size).toString()))
        TextButton(onClick = {
            raw = Json.encodeToString(config.taskPlan)
            base = raw
            error = null
            open = true
        }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fe1eeafc33)) }
        DelegateModelDropdown(profiles = profiles, selectedProfileUid = inspectedUid, enabled = true, onSelected = { selected ->
            inspectedUid = selected?.uid.orEmpty()
            inspect = selected
        }, automaticLabel = androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_picker_inspect), roleLabel = androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_picker_readiness), testTag = "readiness_profile_dropdown")
    }
    inspect?.let { profile ->
        val readiness = remember(profile, config) { DelegationReadinessInspector.inspect(profile, config) }
        AlertDialog(onDismissRequest = { inspect = null }, title = { Text(profile.name) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(readiness.identity.canonical ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6a87db4e77))
                Text(readiness.identity.provenance)
                readiness.checks.forEach { (check, value) ->
                    val label = androidx.compose.ui.res.stringResource(
                        when (check) {
                            dev.chungjungsoo.gptmobile.data.delegation.ReadinessCheck.PROFILE_ENABLED -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_profile
                            dev.chungjungsoo.gptmobile.data.delegation.ReadinessCheck.MODEL_SELECTED -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_model
                            dev.chungjungsoo.gptmobile.data.delegation.ReadinessCheck.LOCAL_ARTIFACT -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_artifact
                            dev.chungjungsoo.gptmobile.data.delegation.ReadinessCheck.CREDENTIALS -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_credentials
                            dev.chungjungsoo.gptmobile.data.delegation.ReadinessCheck.PROVIDER_REACHABILITY -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_provider
                            dev.chungjungsoo.gptmobile.data.delegation.ReadinessCheck.BACKEND_QUALIFICATION -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_backend
                        }
                    )
                    val status = androidx.compose.ui.res.stringResource(
                        when (value) {
                            true -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_yes
                            false -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_no
                            null -> dev.chungjungsoo.gptmobile.R.string.v0950_ready_unknown
                        }
                    )
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_ready_result, label, status))
                }
                readiness.blockers.forEach { Text(it.name.replace('_', ' '), color = MaterialTheme.colorScheme.error) }
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_dcba27a1e4))
            }
        }, confirmButton = { TextButton(onClick = { inspect = null }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d7cf5045c0)) } })
    }
    if (open) {
        val plan = remember(raw) { Json.decodeFromString<DelegationTaskPlan>(raw) }
        fun update(value: DelegationTaskPlan) {
            raw = Json.encodeToString(value)
            error = null
        }
        fun edit(id: String, transform: (DelegationPlanStep) -> DelegationPlanStep) = update(plan.copy(steps = plan.steps.map { if (it.id == id) transform(it) else it }))
        AlertDialog(onDismissRequest = { open = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ffbf26d451)) }, text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f8a03a7590)) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                itemsIndexed(plan.steps, key = { _, step -> step.id }) { index, step ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_25c1878e0d, index + 1), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(step.instruction, { text -> edit(step.id) { it.copy(instruction = text.take(1000)) } }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f27d6fd145)) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DelegationTemplate.Role.entries.forEach { role -> FilterChip(step.role == role, { edit(step.id) { it.copy(role = role) } }, label = { Text(role.name.lowercase().replace('_', ' ')) }) } }
                        DelegateModelDropdown(profiles = profiles, blockedReason = { candidate -> dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.reasons(candidate, config, reviewer = profiles.find { it.uid == config.reviewerProfileUid }).takeIf { it.isNotEmpty() }?.joinToString { it.name.replace('_', ' ') } }, selectedProfileUid = step.workerUid, enabled = true, onSelected = { selected -> edit(step.id) { it.copy(workerUid = selected?.uid.orEmpty()) } }, automaticLabel = androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_picker_approved), roleLabel = androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_picker_worker), testTag = "plan_worker_${step.id}")
                        var tokenText by rememberSaveable(step.id) { mutableStateOf(step.outputTokens.toString()) }
                        OutlinedTextField(tokenText, { text ->
                            tokenText = text
                            edit(step.id) { it.copy(outputTokens = text.toIntOrNull() ?: 0) }
                        }, isError = step.outputTokens !in 128..4096, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_af69243f28)) })
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_913ca2eb9e))
                        FlowRow {
                            plan.steps.filter { it.id != step.id }.forEach { dependency ->
                                FilterChip(dependency.id in step.dependsOn, { edit(step.id) { it.copy(dependsOn = if (dependency.id in it.dependsOn) it.dependsOn - dependency.id else it.dependsOn + dependency.id) } }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f8279a9182, plan.steps.indexOf(dependency) + 1)) })
                            }
                        }
                        TextButton(onClick = { update(plan.copy(steps = plan.steps.filterNot { it.id == step.id }.map { it.copy(dependsOn = it.dependsOn - step.id) })) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b243b9910e, index + 1)) }
                    }
                }
                item { TextButton(enabled = plan.steps.size < 12, onClick = { update(plan.copy(steps = plan.steps + DelegationPlanStep(UUID.randomUUID().toString()))) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0d550f39b8)) } }
                item { TextButton(onClick = { update(DelegationTaskPlan()) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b943aa3521)) } }
            }
        }, confirmButton = {
            TextButton(onClick = {
                try {
                    plan.validate()
                    if (Json.encodeToString(config.taskPlan) != base) {
                        error = "The saved plan changed. Close and reopen this editor to review it."
                    } else {
                        onChange { latest ->
                            check(Json.encodeToString(latest.taskPlan) == base) { "Task plan changed elsewhere" }
                            latest.copy(taskPlan = plan)
                        }
                        open = false
                    }
                } catch (failure: IllegalArgumentException) {
                    error = failure.message
                } catch (failure: IllegalStateException) {
                    error = failure.message
                }
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_283285f828)) }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c0774c1850)) } })
    }
}
