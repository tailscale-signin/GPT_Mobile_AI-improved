package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSettingsReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GenerationConfigReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig

/** App-applied instrumentation and compatibility choices are not native confirmations. */
@Composable
internal fun BenchmarkAppAppliedOverrides(settings: BenchmarkSettingsReceipt) {
    Text("App-applied instrumentation", style = MaterialTheme.typography.titleSmall)
    if (settings.appAppliedInstrumentationOverrides.isEmpty()) Text("Instrumentation overrides: Not recorded", style = MaterialTheme.typography.bodySmall)
    settings.appAppliedInstrumentationOverrides.toSortedMap().forEach { (name, value) -> Text("${appliedSettingName(name)}: $value", style = MaterialTheme.typography.bodySmall) }
    Text("App-applied structured-output overrides", style = MaterialTheme.typography.titleSmall)
    if (settings.appAppliedSafetyOverrides.isEmpty()) Text("Structured-output overrides: None recorded", style = MaterialTheme.typography.bodySmall)
    settings.appAppliedSafetyOverrides.toSortedMap().forEach { (name, value) -> Text("${appliedSettingName(name)}: $value", style = MaterialTheme.typography.bodySmall) }
    Text("These fields record what the app applied. Native-confirmed values require their own acknowledgement below.", style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun BenchmarkAppAppliedGeneration(config: GenerationConfigReceipt) {
    Text("App-applied generation configuration", style = MaterialTheme.typography.titleSmall)
    Text("Requested sampler: ${appliedSamplerText(config.requestedSampler)}", style = MaterialTheme.typography.bodySmall)
    Text("App-applied sampler: ${appliedSamplerText(config.appliedSampler)}", style = MaterialTheme.typography.bodySmall)
    Text("Requested output budget: ${config.requestedOutputTokens ?: "Unknown"} · app-applied output budget: ${config.appliedOutputTokens ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
    Text("Thinking requested: ${settingBoolean(config.requestedThinking)} · app-applied: ${settingBoolean(config.appliedThinking)}", style = MaterialTheme.typography.bodySmall)
    Text("Speculative decoding / MTP requested: ${settingBoolean(config.requestedSpeculativeDecoding)} · app-applied: ${settingBoolean(config.appliedSpeculativeDecoding)}", style = MaterialTheme.typography.bodySmall)
    Text("Requested schema digest: ${config.requestedResponseSchemaDigest ?: "Unknown"}\nApp-applied schema digest: ${config.appliedResponseSchemaDigest ?: "Unknown"}\nStructured mode: ${config.structuredOutputMode ?: "Unknown"}\nConfiguration source: ${config.configurationSource ?: "Not recorded"}", style = MaterialTheme.typography.labelSmall)
    val schema = config.effectiveResponseSchemaDigest?.takeIf { "effectiveResponseSchemaDigest" in config.nativeAcknowledgedFields }
    Text("Native-confirmed schema digest: ${schema ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
}

private fun appliedSettingName(name: String): String = when (name) {
    "nativeMetricsEnabled" -> "Native metrics collection enabled"
    "thinking" -> "Thinking enabled"
    "speculativeDecoding" -> "Speculative decoding / MTP enabled"
    else -> name
}

private fun settingBoolean(value: Boolean?): String = when (value) {
    true -> "Enabled"
    false -> "Disabled"
    null -> "Unknown"
}
private fun appliedSamplerText(value: LocalSamplerConfig?): String = value?.let { "temperature ${it.temperature} · top-p ${it.topP} · top-k ${it.topK}" } ?: "Unknown"
