package dev.chungjungsoo.gptmobile.llama

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AdvancedSettings(
    // Connection Settings
    var serverUrl: String = "http://localhost:8080/v1/",
    var apiTimeout: Int = 30000,
    var maxRetries: Int = 3,
    var retryDelayMs: Int = 1000,

    // Router Mode Settings
    var routerModeEnabled: Boolean = true,
    var modelsMax: Int = 4,
    var modelsAutoload: Boolean = true,
    var modelsDir: String = "",
    var modelsPreset: String = "",

    // Model Selection (Router Mode)
    var selectedModel: String = "llama3",
    var modelAliases: String = "",
    var modelTags: String = "",

    // Inference Settings
    var nGpuLayers: Int = 35,
    var nThreads: Int = 8,
    var nBatch: Int = 512,
    var nCtx: Int = 8192,
    var nParallel: Int = 4,
    var kvUnified: Boolean = true,

    // Generation Parameters
    var temperature: Float = 0.7f,
    var topP: Float = 0.95f,
    var topK: Int = 40,
    var minP: Float = 0.05f,
    var typicalP: Float = 1.0f,
    var repeatLastN: Int = 64,
    var repeatPenalty: Float = 1.1f,
    var frequencyPenalty: Float = 0.0f,
    var presencePenalty: Float = 0.0f,
    var mirostat: Int = 0,
    var mirostatTau: Float = 5.0f,
    var mirostatEta: Float = 0.1f,
    var penalizeNewline: Boolean = true,

    // Sampling
    var seed: Long = -1,
    var stop: String = "",
    var stopSpecialTokens: String = "",
    var grammar: String = "",

    // Context & Memory
    var nPredict: Int = -1,
    var nKeep: Int = 0,
    var nHistory: Int = 0,
    var nHistoryMax: Int = 0,

    // LoRA Settings
    var loraBase: String = "",
    var loraScale: Float = 1.0f,
    var loraPrompt: String = "",

    // Embedding Settings
    var embeddingEnabled: Boolean = false,
    var embeddingBatchSize: Int = 8,

    // Performance
    var useMmap: Boolean = true,
    var useMlock: Boolean = false,
    var useVllm: Boolean = false,
    var useFlashAttn: Boolean = false,

    // Logging & Debug
    var logLevel: String = "info",
    var verbose: Boolean = false,
    var showMetrics: Boolean = true,
    var enableCORS: Boolean = false,

    // Gateway v10 / GPT Mobile integration
    var gatewayIntegrationEnabled: Boolean = true,
    var gatewayPerformanceProfile: String = "high_performance",
    var gatewayToolRouting: String = "auto",
    var gatewayCachePrompt: Boolean = true,
    var gatewayCacheReuse: Int = 512,
    var gatewaySlotAffinity: Boolean = true,
    var gatewaySlotCount: Int = 0,
    var gatewayMaxRounds: Int = 160,
    var gatewayAdaptiveReasoning: Boolean = true,
    var gatewayThinkingBudget: Int = -1,
    var gatewayStreamHeartbeatSeconds: Int = 4,
    var gatewayProgressPollMs: Int = 250,
    var gatewayModelWaitAfterSeconds: Int = 10,
    var gatewayModelWaitIntervalSeconds: Int = 15,
    var gatewayJobTimeoutSeconds: Int = 5400,
    var gatewayProgressDetail: String = "normal",

    // Advanced
    var useCache: Boolean = true,
    var cacheDir: String = "",
    var stopTimeout: Int = 60,
    var maxInstances: Int = 4,
    var defaultGenerationSettings: String = "{}",
    var webuiSettings: String = "{}"
) : Parcelable
