package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@HiltViewModel
class ConnectionDoctorViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val network: NetworkClient,
    private val gateway: GatewayAPI,
    private val runtime: LocalRuntime,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val detectedContext = MutableStateFlow<Pair<String, Int>?>(null)
    fun applyDetectedContext() = action {
        val detected = detectedContext.value ?: return@action
        settings.mutateFeatureSettings { current -> current.copy(tokenBudget = current.tokenBudget.copy(profileContextCeilings = current.tokenBudget.profileContextCeilings + detected)) }
        detectedContext.value = null
        finish("Context ceiling saved for this profile. Your global context budget still applies.")
    }
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val preferences = context.getSharedPreferences("connection_doctor", Context.MODE_PRIVATE)
    val report = MutableStateFlow("Choose a profile to check its connection. Benchmarks are in Debug and Statistics → Benchmarks.")
    val contextReport = MutableStateFlow("Inspect a profile to see verified and declared context limits.")
    val diagnosticReport = MutableStateFlow("Inspection does not generate an answer or reconfigure the server.")
    private val llamaCapacity = dev.chungjungsoo.gptmobile.data.context.LlamaCapacityRepository(network)
    init {
        // Older versions persisted arbitrary inspection text in plaintext preferences.
        preferences.edit().remove("last_inspection").apply()
    }
    val busy = MutableStateFlow(false)
    private var job: Job? = null
    fun cancel() {
        job?.cancel()
    }
    fun inspect(profile: PlatformV2) = action {
        detectedContext.value = null
        contextReport.value = "Context details for ${profile.name} are not verified yet."
        diagnosticReport.value = "Checking ${profile.name}. Inspection does not generate an answer or change server configuration."
        val destination = if (profile.compatibleType == ClientType.LITERT_LM) "ON_DEVICE" else endpointLocality(profile.apiUrl).name
        val lines = mutableListOf("Destination: $destination", "Profile: ${profile.name} · ${profile.model}")
        if (profile.compatibleType == ClientType.LITERT_LM) {
            lines += "Actual runtime: ${runtime.state.value.backend ?: "not loaded"}"
            lines += "Accelerator: ${runtime.state.value.engineSpec?.accelerator ?: "not loaded"}"
            runtime.state.value.fallbackReason?.let { lines += "Fallback: $it" }
            contextReport.value = "Loaded local context: ${runtime.admittedEngineSpec()?.maxTokens ?: "unknown"} tokens. Requested output cap: ${profile.maxTokens ?: "inherited"}. Model context is independent of the output cap."
            diagnosticReport.value = "Runtime phase: ${runtime.state.value.lifecycle}. Actual backend and artifact must pass their own readiness checks. This inspection does not load or download a model."
        } else {
            require(!profile.apiUrl.contains('?') && !profile.apiUrl.contains('@')) { "Use a clean provider URL and the credential field." }
            val base = profile.apiUrl.trimEnd('/')
            val modelsUrl = if (profile.compatibleType == ClientType.OLLAMA && !base.endsWith("/v1")) "$base/api/tags" else "$base/models"
            val response = network().get(modelsUrl) { profile.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) } }
            val modelBody = response.bodyAsText()
            lines += "Model discovery: HTTP ${response.status.value} (${modelBody.length} response characters)"
            if (response.status.value == 200) {
                dev.chungjungsoo.gptmobile.data.context.discoverContextCeiling(modelBody, profile.model)?.let { ceiling ->
                    detectedContext.value = profile.uid to ceiling
                    lines += "Server reports a context ceiling of $ceiling tokens. Apply it below to constrain this profile."
                }
            }
            if (response.status.value in setOf(401, 403)) lines += "Authentication was rejected. Reconnect or replace the credential."
            if (profile.compatibleType == ClientType.LLAMA) {
                val capabilities = try {
                    gateway.getCapabilities(ProviderRequestConfig(profile.apiUrl, profile.token))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                lines += if (capabilities == null) "Route: llama.cpp-compatible; direct/router identity unverified. Missing Gateway metadata does not mean chat is offline." else "Route: Gateway capability endpoint available. Gateway prompt transformations require separate verification."
                llamaCapacity.invalidate()
                val capacity = llamaCapacity.discover(ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid, providerType = "LLAMA"), profile.model)
                val features = settings.getFeatureSettings()
                val declared = features.tokenBudget.profileContextCeilings[profile.uid]?.takeIf { it > 0 }
                val global = features.tokenBudget.normalized().contextTokens.takeIf { it != Int.MAX_VALUE }
                val effective = listOfNotNull(capacity?.contextTokens, declared?.toLong(), global?.toLong()).minOrNull()
                val output = dev.chungjungsoo.gptmobile.data.context.RequestLimitResolver.output(profile.maxTokens, features.tokenBudget.outputTokens)
                val reasoning = features.llamaReasoningModes[profile.uid] ?: if (profile.reasoning) dev.chungjungsoo.gptmobile.data.context.ReasoningMode.ON else dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO
                contextReport.value = listOf(
                    "Verified serving allocation: ${capacity?.contextTokens ?: "unknown"} tokens",
                    "Manual profile ceiling: ${declared ?: "not declared"}",
                    "Global ceiling: ${global ?: "no additional app cap"}",
                    "Effective ceiling: ${effective ?: "unknown"}",
                    "Output allowance: ${output.tokens ?: "inherited"}; sources: ${output.sources.joinToString().ifBlank { "provider/app defaults" }}",
                    "Reasoning requested: $reasoning. Template control is checked when a request needs explicit On/Off; observed computation is separate.",
                    "Fit requires full prompt + generated-token allowance + safety/uncertainty ≤ serving context. Future tool headroom is planning space, not a second charge for serialized evidence.",
                    "Overflow preserves the current task and returns a shortfall. Increase verified context or choose an explicit segmented workflow."
                ).joinToString("\n\n")
                diagnosticReport.value = listOf(
                    "Capacity source: ${capacity?.provenance ?: "UNKNOWN"}; metadata refreshed on this check.",
                    "Allocation: ${capacity?.fingerprint?.allocationRevision ?: "unknown"}. Slot count is not used to divide n_ctx.",
                    "Loaded-model context and a model catalog limit are different. Missing or sleeping model metadata remains unknown.",
                    "Waiting: 180 s to first meaningful progress; 90 s meaningful-progress idle allowance. Pings do not extend the deadline. User total-generation setting: ${features.tokenBudget.generationTimeoutMinutes.takeIf { it > 0 }?.let { "$it minutes" } ?: "no explicit deadline"}.",
                    "Connection/authentication failure, unavailable model, context overflow, reasoning-only output, stalled decoding and output-limit exhaustion require different recovery actions. Details and saved evidence remain attached to the response.",
                    "On-device Assist uses its own artifact/backend readiness; a healthy desktop connection does not qualify that runtime."
                ).joinToString("\n\n")
                capacity?.contextTokens?.takeIf { it <= Int.MAX_VALUE }?.let { detectedContext.value = profile.uid to it.toInt() }
            }
            lines += "Streaming and performance tests are in Debug and Statistics → Benchmarks. Check live tool connections in Tool connections."
        }
        finish(lines.joinToString("\n"))
    }
    private fun finish(text: String) {
        val safe = DiagnosticRedactor.redact(text).take(12000)
        report.value = safe
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(180_000) { block() }
            } catch (e: CancellationException) {
                report.value = "Canceled. The request was stopped on this client."
                throw e
            } catch (_: Exception) {
                report.value = "Connection check failed. Verify URL, credentials, server model, Wi-Fi/VPN and local network permission."
            } finally {
                busy.value = false
            }
        }
    }
}
