package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkProfileSettings
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkAcceleratorEvidence
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt

/** Evidence is inspected one case at a time; no current profile value replaces a saved observation. */
@Composable
internal fun BenchmarkConfigurationDetails(run: BenchmarkRun) {
    BenchmarkEvidenceDisclosure("Configuration and runtime receipts", run.id) {
        Text("Request policy: ${run.executionPolicy?.label ?: "Unknown (historical)"}", style = MaterialTheme.typography.titleSmall)
        Text("Fixture set ${run.fixtureSetVersion ?: "Unknown"} · rotation seed ${run.fixtureRotationSeed ?: "Unknown"}\nManifest digest: ${run.fixtureManifestHash ?: "Unknown"}\nDevice build: ${run.deviceBuild ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
        val readiness = run.readiness
        if (readiness == null) Text("Readiness preflight: Not recorded", style = MaterialTheme.typography.bodySmall)
        readiness?.let { observed ->
            Text("Readiness: ${if (observed.ready) "Required checks passed" else "Required checks incomplete or failed"}", style = MaterialTheme.typography.titleSmall)
            observed.checks.forEach { check -> Text("${check.id}: ${check.status.name.lowercase()} · ${benchmarkMetricText(check.durationMs?.toDouble(), "ms", 0)}${check.detail?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            Text("Structured mode: ${run.structuredOutputMode?.name ?: "Unknown"} · original native failure retained: ${observed.nativePrimaryFailureRetained}", style = MaterialTheme.typography.bodySmall)
            if (observed.probes.isNotEmpty()) Text("Preflight probes have separate request identities and are excluded from official case grades.", style = MaterialTheme.typography.labelSmall)
            observed.probes.forEach { probe ->
                val sample = probe.sample
                val status = if (sample == null) {
                    probe.state.lowercase()
                } else if (probe.passed) {
                    "pass"
                } else {
                    "fail"
                }
                Text("Probe ${probe.id} · ${probe.structuredOutputMode} · $status · ${sample?.failure?.code ?: sample?.failureCategory ?: sample?.outcome?.name ?: "No terminal sample recorded"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (run.samples.isEmpty()) {
            Text("No per-case settings or execution receipts were recorded.")
        } else {
            var selectedIndex by remember(run.id) { mutableIntStateOf(run.samples.lastIndex) }
            val index = selectedIndex.coerceIn(run.samples.indices)
            val sample = run.samples[index]
            Text("Case ${index + 1}/${run.samples.size}: ${sample.label}", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selectedIndex = index - 1 }, enabled = index > 0) { Text("Previous case") }
                TextButton(onClick = { selectedIndex = index + 1 }, enabled = index < run.samples.lastIndex) { Text("Next case") }
            }
            BenchmarkCaseConfiguration(run, sample)
        }
    }
}

@Composable
private fun BenchmarkCaseConfiguration(run: BenchmarkRun, sample: BenchmarkSample) {
    val evidence = remember(run, sample) { benchmarkAcceleratorEvidence(run, sample) }
    if (run.local) {
        Text("Execution evidence: ${evidence.status.replace('_', ' ')} · device qualification: ${evidence.qualificationStatus.replace('_', ' ')}", style = MaterialTheme.typography.titleSmall)
        Text("Native entry ${benchmarkObservation(evidence.nativeEntry)} · prefill ${benchmarkObservation(evidence.prefillObserved)} · decode ${benchmarkObservation(evidence.decodeObserved)} · terminal ${benchmarkObservation(evidence.terminalObserved)} · visible output ${benchmarkObservation(evidence.visibleOutput)} · cleanup ${benchmarkObservation(evidence.cleanupConfirmed)}", style = MaterialTheme.typography.bodySmall)
        Text(evidence.scope, style = MaterialTheme.typography.labelSmall)
    }
    Text("End-to-end duration: ${benchmarkMetricText((sample.endToEndDurationMs ?: sample.durationMs.takeIf { it > 0 })?.toDouble(), "ms", 0)} · native generation: ${benchmarkMetricText(sample.generationDurationMs?.toDouble(), "ms", 0)} · first visible text: ${benchmarkMetricText(sample.firstTextMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
    Text("Admission wait: ${benchmarkMetricText(sample.admissionWaitMs?.toDouble(), "ms", 0)} · cleanup: ${benchmarkMetricText(sample.cleanupDurationMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
    if (sample.phaseElapsedMs.isNotEmpty()) Text("Observed phases: ${sample.phaseElapsedMs.entries.joinToString { "${it.key}: ${it.value} ms" }}", style = MaterialTheme.typography.labelSmall)
    sample.inspectionReceipt?.let { inspection ->
        BenchmarkEvidenceDisclosure("Latest inspection invocation", inspection.inspectionId) {
            Text("${inspection.outcome} · total ${benchmarkMetricText(inspection.totalMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.titleSmall)
            Text("This receipt covers the latest inspection invocation. Whole-case preparation remains in the observed phases above; multiple inspections may occur during a case.", style = MaterialTheme.typography.bodySmall)
            Text(
                "Inspection joined to this case: ${when (sample.inspectionAttribution?.joined) {
                    true -> "Confirmed"
                    false -> "Not confirmed"
                    null -> "Not recorded"
                }} · ${sample.inspectionAttribution?.basis ?: "No attribution receipt"}",
                style = MaterialTheme.typography.labelSmall
            )
            Text("Metadata cache: ${inspection.metadataCacheDisposition} · digest cache: ${inspection.digestCacheDisposition}", style = MaterialTheme.typography.bodySmall)
            Text("File resolution ${benchmarkMetricText(inspection.fileResolutionMs?.toDouble(), "ms", 0)} · file validation ${benchmarkMetricText(inspection.fileValidationMs?.toDouble(), "ms", 0)} · digest validation ${benchmarkMetricText(inspection.digestValidationMs?.toDouble(), "ms", 0)} · digest lock wait ${benchmarkMetricText(inspection.digestLockWaitMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
            Text("Model bytes hashed: ${inspection.modelBytesHashed ?: "Not measured"} · metadata wait ${benchmarkMetricText(inspection.metadataCacheWaitMs?.toDouble(), "ms", 0)} · opaque metadata inspection ${benchmarkMetricText(inspection.opaqueMetadataMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
            Text("Tokenizer metadata: ${benchmarkMetricText(inspection.tokenizerMetadataMs?.toDouble(), "ms", 0)} · ${inspection.tokenizerTimingStatus.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            Text("Compatibility ${benchmarkMetricText(inspection.compatibilityValidationMs?.toDouble(), "ms", 0)} · journal ${benchmarkMetricText(inspection.journalMs?.toDouble(), "ms", 0)} · service binding ${benchmarkMetricText(inspection.serviceBindingMs?.toDouble(), "ms", 0)} (${inspection.serviceBindingDisposition ?: "Unknown"}) · transport ${benchmarkMetricText(inspection.transportRoundTripMs?.toDouble(), "ms", 0)} · admission lease wait ${benchmarkMetricText(inspection.admissionLeaseWaitMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
            Text("Inspection ${inspection.inspectionId}\nArtifact ${inspection.artifactDigest ?: "Unknown"}\nRuntime ${inspection.runtimeIdentity}\nBackend ${inspection.backend}\nConfiguration ${inspection.configurationDigest}\nDevice ${inspection.deviceIdentity}\nFirmware ${inspection.firmwareDigest}\nWorker ${inspection.workerGenerationId ?: "Unknown"}\nRequest ${inspection.runtimeRequestId ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
        }
    }
    BenchmarkDeadlineDetails(run, sample)
    val settings = sample.settingsReceipt
    if (settings == null) {
        Text("Saved profile settings and benchmark overrides: Not recorded", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Saved profile settings", style = MaterialTheme.typography.titleSmall)
        BenchmarkProfileConfiguration(settings.profile)
        Text("Requested benchmark settings", style = MaterialTheme.typography.titleSmall)
        BenchmarkProfileConfiguration(settings.requested)
        Text("Overridden fields: ${settings.overriddenFields.sorted().joinToString().ifBlank { "None recorded" }}", style = MaterialTheme.typography.bodySmall)
        Text("Requested schema digest: ${settings.responseSchemaDigest ?: "None recorded"}\nStructured mode: ${settings.structuredOutputMode?.name ?: "Not recorded"}\nVerified compatibility key: ${settings.verifiedStructuredCompatibilityKey ?: "Not recorded"}", style = MaterialTheme.typography.labelSmall)
        BenchmarkAppAppliedOverrides(settings)
        Text(settings.acknowledgementScope, style = MaterialTheme.typography.labelSmall)
    }
    Text(
        "Resident engine before execution: ${when (sample.residentMatch?.exactMatch) {
            true -> "Exact identity matched"
            false -> "Not the same ready engine"
            null -> "Unknown"
        }} · ${sample.residentMatch?.regime ?: "Not recorded"}",
        style = MaterialTheme.typography.bodySmall
    )
    Text("Native loading occurred in this attempt: ${benchmarkObservation(sample.nativeLoading?.occurred)} · native load ${benchmarkMetricText(sample.nativeLoading?.durationMs?.toDouble(), "ms", 0)} · client loading phase ${benchmarkMetricText(sample.nativeLoading?.clientLoadingPhaseMs?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
    sample.nativeLoading?.reason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    if (sample.runtimeExecutions.isEmpty()) Text("Actual native settings, driver and engine identity: Unknown", style = MaterialTheme.typography.bodySmall)
    sample.runtimeExecutions.forEachIndexed { index, execution ->
        Text("Physical generation ${index + 1}", style = MaterialTheme.typography.titleSmall)
        val load = execution.engineLoad
        Text("Recorded engine backend: ${load?.actualBackend ?: "Unknown"} · runtime: ${load?.runtimeRevision ?: "Unknown"}\nDriver: ${load?.driverIdentity ?: "Unknown"}\nDevice: ${load?.deviceName ?: "Unknown"} (${load?.deviceType ?: "Unknown"}) · registry: ${load?.backendRegistry ?: "Unknown"}\nRecorded offloaded layers: ${load?.actualOffloadLayers ?: "Unknown"}\nLoad receipt source: ${load?.configurationSource ?: "Not recorded"}", style = MaterialTheme.typography.bodySmall)
        Text("Native entry: ${benchmarkObservation(execution.nativeEntered)} · native return: ${benchmarkObservation(execution.nativeReturned)} · terminal: ${execution.terminalKind ?: "Unknown"}\nArtifact: ${load?.artifactDigest ?: "Unknown"}\nWorker: ${execution.workerGenerationId ?: "Unknown"}\nEngine: ${execution.engineInstanceId ?: "Unknown"}\nOperation: ${execution.nativeOperationId ?: "Unknown"}\nRequest: ${execution.runtimeRequestId ?: "Unknown"}\nPhysical attempt: ${execution.physicalAttemptId ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
        execution.configuration?.let { config ->
            BenchmarkAppAppliedGeneration(config)
            Text("Native acknowledged generation settings", style = MaterialTheme.typography.titleSmall)
            Text("Temperature ${acknowledged(config, "sampler.temperature", config.effectiveSampler?.temperature)} · top-p ${acknowledged(config, "sampler.topP", config.effectiveSampler?.topP)} · top-k ${acknowledged(config, "sampler.topK", config.effectiveSampler?.topK)}", style = MaterialTheme.typography.bodySmall)
            Text("Output budget ${acknowledged(config, "effectiveOutputTokens", config.effectiveOutputTokens)} · context ${acknowledged(config, "contextTokens", config.contextTokens)} · prompt ${acknowledged(config, "measuredPromptTokens", config.measuredPromptTokens)} · safety ${acknowledged(config, "safetyTokens", config.safetyTokens)}", style = MaterialTheme.typography.bodySmall)
            Text("CPU threads ${acknowledged(config, "cpuThreads", config.cpuThreads)} · prompt threads ${acknowledged(config, "promptThreads", config.promptThreads)} · batch ${acknowledged(config, "batchTokens", config.batchTokens)} · microbatch ${acknowledged(config, "microBatchTokens", config.microBatchTokens)}", style = MaterialTheme.typography.bodySmall)
            Text("Tokenizer ${acknowledged(config, "tokenizerIdentity", config.tokenizerIdentity)}\nTemplate ${acknowledged(config, "templateIdentity", config.templateIdentity)}\nAcknowledged fields: ${config.nativeAcknowledgedFields.sorted().joinToString().ifBlank { "None recorded" }}", style = MaterialTheme.typography.labelSmall)
        } ?: Text("Native acknowledged generation settings: Unknown", style = MaterialTheme.typography.bodySmall)
    }
    Text("Cleanup proof: ${if (sample.runtimeCleanup?.safeForSuccessor == true) "Safe for successor confirmed" else sample.runtimeCleanup?.failureReason ?: "Unknown or incomplete"}", style = MaterialTheme.typography.bodySmall)
    sample.runtimeCleanup?.takeIf { it.terminationRequested }?.let { cleanup ->
        Text("Worker termination requested · exit ${benchmarkObservation(cleanup.workerExitConfirmed)} · evidence ${cleanup.workerExitEvidence ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
    }
    sample.toolAssessment?.let { tool ->
        Text("Tool-use dimensions", style = MaterialTheme.typography.titleSmall)
        Text("Selection ${benchmarkGradeObservation(tool.toolSelectionCorrect)} · arguments ${benchmarkGradeObservation(tool.argumentsCorrect)} · execution ${benchmarkGradeObservation(tool.executionSuccessful)} · result used ${benchmarkGradeObservation(tool.resultUsed)}", style = MaterialTheme.typography.bodySmall)
        Text("Final format ${benchmarkGradeObservation(tool.finalFormatCompliant)} · progress protocol ${benchmarkGradeObservation(tool.progressProtocolCompliant)} · expected retry ${benchmarkGradeObservation(tool.expectedRetryObserved)} · call limit ${benchmarkGradeObservation(tool.callLimitRespected)}", style = MaterialTheme.typography.bodySmall)
        Text("${tool.attemptedCalls} attempted calls · ${tool.successfulCalls} successful · ${tool.expectedErrorCalls} expected errors · ${tool.policy}", style = MaterialTheme.typography.labelSmall)
    }
    BenchmarkEvidenceDisclosure("Retained raw answer", sample.attemptId ?: sample.testId) {
        val raw = sample.canonicalAnswer ?: sample.preview.takeIf { it.isNotEmpty() }
        if (raw == null) {
            Text("Raw output not retained.")
        } else {
            Text("Original retained output; formatting errors are preserved and no diagnostic repair changes the official grade.", style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(raw.take(8192), style = MaterialTheme.typography.bodySmall) }
            if (raw.length > 8192 || sample.answerTruncated || sample.canonicalAnswer == null && raw.length < sample.outputCharacters) Text("This view shows a bounded excerpt. Export the run to inspect all retained evidence and truncation metadata.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BenchmarkProfileConfiguration(settings: BenchmarkProfileSettings) {
    Text("Model ${settings.model} · temperature ${settings.temperature ?: "Unknown"} · top-p ${settings.topP ?: "Unknown"} · top-k ${settings.topK ?: "Unknown"} · output budget ${settings.outputTokens ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
    Text("Reasoning ${settings.reasoning} · streaming ${settings.streaming} · tools disabled ${settings.toolsDisabled} · accelerator preference ${settings.requestedAccelerator ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
    Text("System prompt digest: ${settings.systemPromptDigest}\nProvider options digest: ${settings.providerOptionsDigest}", style = MaterialTheme.typography.labelSmall)
}

private fun acknowledged(config: GenerationConfigReceipt, key: String, value: Any?): String = value?.takeIf { key in config.nativeAcknowledgedFields }?.toString() ?: "Unknown"
private fun benchmarkObservation(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}
private fun benchmarkGradeObservation(value: Boolean?): String = when (value) {
    true -> "Pass"
    false -> "Fail"
    null -> "Not measured"
}
