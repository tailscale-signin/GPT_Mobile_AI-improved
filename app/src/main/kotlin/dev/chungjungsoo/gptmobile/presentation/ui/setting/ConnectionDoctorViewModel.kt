package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
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
    private val chats: ChatRepository,
    private val runtime: LocalRuntime,
    ledger: dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val detectedContext = MutableStateFlow<Pair<String, Int>?>(null)
    fun applyDetectedContext() = action {
        val detected = detectedContext.value ?: return@action
        val current = settings.getFeatureSettings()
        settings.updateFeatureSettings(current.copy(tokenBudget = current.tokenBudget.copy(profileContextCeilings = current.tokenBudget.profileContextCeilings + detected)))
        detectedContext.value = null
        finish("Context ceiling saved for this profile. Your global context budget still applies.")
    }
    val invocations = ledger.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profiles = settings.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val preferences = context.getSharedPreferences("connection_doctor", Context.MODE_PRIVATE)
    val report = MutableStateFlow(preferences.getString("last_report", "Choose a profile, then check the connection or run the fixed benchmark.").orEmpty())
    val busy = MutableStateFlow(false)
    private var job: Job? = null
    fun cancel() {
        job?.cancel()
    }
    fun inspect(profile: PlatformV2) = action {
        detectedContext.value = null
        val destination = if (profile.compatibleType == ClientType.LITERT_LM) "ON_DEVICE" else endpointLocality(profile.apiUrl).name
        val lines = mutableListOf("Destination: $destination", "Profile: ${profile.name} · ${profile.model}")
        if (profile.compatibleType == ClientType.LITERT_LM) {
            lines += "Actual runtime: ${runtime.state.value.backend ?: "not loaded"}"
            lines += "Accelerator: ${runtime.state.value.engineSpec?.accelerator ?: "not loaded"}"
            runtime.state.value.fallbackReason?.let { lines += "Fallback: $it" }
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
                val capabilities = gateway.getCapabilities(ProviderRequestConfig(profile.apiUrl, profile.token))
                lines += if (capabilities == null) "No Gateway capability response; this may be a direct llama.cpp server." else "Gateway capabilities: $capabilities"
            }
            lines += "Streaming is checked by the explicit benchmark. Tools are checked in Tool connections. Cancel the benchmark to test client cancellation; server cancellation depends on its protocol."
        }
        finish(lines.joinToString("\n"))
    }
    fun benchmark(profile: PlatformV2) = action {
        val start = SystemClock.elapsedRealtime()
        var first = 0L
        var setup = 0L
        var characters = 0
        var outputTokens: Int? = null
        var peakPss = 0
        val initialThermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        val target = profile.copy(
            disableAllTools = true,
            reasoning = false,
            temperature = 0f,
            maxTokens = if (profile.compatibleType == ClientType.LITERT_LM) profile.maxTokens else 256,
            systemPrompt = "Benchmark. Follow the user instruction concisely. Do not use tools."
        )
        chats.completeChat(listOf(MessageV2(content = BENCHMARK_PROMPT, platformType = null)), emptyList(), target, "benchmark-${System.currentTimeMillis()}").collect { event ->
            when (event) {
                is ApiState.PhaseChanged -> if (setup == 0L) setup = SystemClock.elapsedRealtime() - start
                is ApiState.Success -> {
                    if (first == 0L) first = SystemClock.elapsedRealtime()
                    characters += event.textChunk.length
                }
                is ApiState.TokenUsage -> outputTokens = event.outputTokens
                is ApiState.Error -> error(event.message)
                else -> Unit
            }
            peakPss = maxOf(peakPss, Debug.getPss().toInt())
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        val tokens = outputTokens ?: ((characters + 3) / 4)
        val inference = (SystemClock.elapsedRealtime() - first).coerceAtLeast(1)
        finish(
            "Benchmark v1 · ${profile.name} · ${profile.model}\n" +
                "First text: ${if (first == 0L) "none" else "${first - start} ms"}\nTotal: $elapsed ms\nSetup/queue: $setup ms\n" +
                "Output: $tokens tokens (${if (outputTokens == null) "estimated" else "reported"}), ${"%.1f".format(tokens * 1000.0 / inference)} tokens/s\n" +
                "Peak sampled client process PSS: $peakPss KiB\nThermal: $initialThermal → ${context.getSystemService(PowerManager::class.java).currentThermalStatus}\n" +
                (if (profile.compatibleType == ClientType.LITERT_LM) "Actual backend: ${runtime.state.value.backend}; accelerator: ${runtime.state.value.engineSpec?.accelerator}\nFallback: ${runtime.state.value.fallbackReason ?: "none reported"}\n" else "Remote backend and accelerator are not reported by this endpoint.\n") +
                "Same prompt, warm cache if already loaded. Setup includes queue/loading/prefill; it is not isolated model load time. On-server RAM is unavailable."
        )
    }
    private fun finish(text: String) {
        val safe = DiagnosticRedactor.redact(text).take(12000)
        report.value = safe
        preferences.edit().putString("last_report", safe).apply()
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
    companion object {
        const val BENCHMARK_PROMPT = "Explain in exactly five short sentences how rain forms. Use plain English."
    }
}
