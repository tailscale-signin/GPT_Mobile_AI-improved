package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ConnectionDoctorDialog(onDismiss: () -> Unit, model: ConnectionDoctorViewModel = hiltViewModel()) {
    val detectedContext by model.detectedContext.collectAsStateWithLifecycle()
    val selectedNvidia by model.lastInspectedNvidia.collectAsStateWithLifecycle()
    val deadlineProfile by model.deadlineProfile.collectAsStateWithLifecycle()
    val deadlineSettings by model.deadlineSettings.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val report by model.report.collectAsStateWithLifecycle()
    val contextReport by model.contextReport.collectAsStateWithLifecycle()
    val diagnosticReport by model.diagnosticReport.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val busy by model.busy.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = {
        model.cancel()
        onDismiss()
    }, title = { Text(stringResource(R.string.connection_doctor_dialog_label_1)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.connection_doctor_dialog_label_2))
            profiles.filter { it.enabled }.forEach { profile ->
                Text(profile.name, Modifier.padding(top = 12.dp))
                TextButton(enabled = !busy, onClick = { model.inspect(profile) }) { Text(stringResource(R.string.connection_doctor_dialog_label_3)) }
            }
            selectedNvidia?.let { profile ->
                TextButton(enabled = !busy, onClick = { model.testNvidiaInference(profile) }) {
                    Text("Test NVIDIA inference · may use API credits")
                }
            }
            if (detectedContext != null) TextButton(enabled = !busy, onClick = model::applyDetectedContext) { Text(stringResource(R.string.context_apply_detected)) }
            if (busy) TextButton(onClick = model::cancel) { Text(stringResource(R.string.connection_doctor_dialog_label_5)) }
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Connection", "Context & Output", "Diagnostics").forEachIndexed { index, label ->
                    androidx.compose.material3.Tab(selected = index == tab, onClick = { tab = index }, text = { Text(label) })
                }
            }
            if (tab == 2 && deadlineProfile != null) {
                var first by rememberSaveable(deadlineProfile?.uid, deadlineSettings) { mutableStateOf(deadlineSettings.firstProgressMillis?.div(1000)?.toString().orEmpty()) }
                var idle by rememberSaveable(deadlineProfile?.uid, deadlineSettings) { mutableStateOf(deadlineSettings.meaningfulIdleMillis?.div(1000)?.toString().orEmpty()) }
                var queue by rememberSaveable(deadlineProfile?.uid, deadlineSettings) { mutableStateOf(deadlineSettings.queueMillis?.div(1000)?.toString().orEmpty()) }
                var load by rememberSaveable(deadlineProfile?.uid, deadlineSettings) { mutableStateOf(deadlineSettings.loadMillis?.div(1000)?.toString().orEmpty()) }
                var round by rememberSaveable(deadlineProfile?.uid, deadlineSettings) { mutableStateOf(deadlineSettings.roundDeadlineMillis?.div(1000)?.toString().orEmpty()) }
                var adaptive by rememberSaveable(deadlineProfile?.uid, deadlineSettings) { mutableStateOf(deadlineSettings.adaptiveFirstProgress) }
                Text(stringResource(R.string.connection_wait_settings_hint), Modifier.padding(top = 12.dp))
                DeadlineSecondsField(stringResource(R.string.connection_wait_first), first, { first = it }, !busy)
                DeadlineSecondsField(stringResource(R.string.connection_wait_idle), idle, { idle = it }, !busy)
                DeadlineSecondsField(stringResource(R.string.connection_wait_queue), queue, { queue = it }, !busy)
                DeadlineSecondsField(stringResource(R.string.connection_wait_load), load, { load = it }, !busy)
                DeadlineSecondsField(stringResource(R.string.connection_wait_round), round, { round = it }, !busy)
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.connection_wait_adaptive), Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = adaptive, onCheckedChange = { adaptive = it }, enabled = !busy)
                }
                TextButton(enabled = !busy, onClick = { model.saveProgressDeadlines(first, idle, queue, load, round, adaptive) }) { Text(stringResource(R.string.connection_wait_save)) }
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    when (tab) {
                        1 -> contextReport
                        2 -> diagnosticReport
                        else -> report
                    }
                )
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            model.cancel()
            onDismiss()
        }) { Text(stringResource(R.string.connection_doctor_dialog_label_7)) }
    })
}

@Composable
private fun DeadlineSecondsField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = Modifier.padding(vertical = 4.dp)
    )
}
