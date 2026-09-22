package dev.melo.gptmobile.improved.data.model.llama

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Information about a Llama model available on a server.
 */
data class LlamaModelInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val provider: String,
    val parameterCountMillions: Int?, // e.g., 7B, 13B, 70B
    val quantizationCode: String, // e.g., Q4_0, Q5_K_M, Q8_0
    val contextWindowTokens: Int,
    val serverLoadPercent: Float = 0f,
    val isAvailable: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getParameterDisplayString(): String =
        parameterCountMillions?.let { "${it}B" } ?: "Unknown"

    fun getQuantizationDisplayString(): String = quantizationCode.replace("_", " ")
}

/**
 * Status information for a Llama model.
 */
data class LlamaModelStatus(
    val modelId: String,
    val isLoaded: Boolean = false,
    val memoryUsageMB: Int = 0,
    val lastUsedAt: Long? = null,
    val error: String? = null
)

/**
 * Configuration for Llama router mode.
 */
data class LlamaRouterConfig(
    val endpoint: String = "http://localhost:11434",
    val gpuOffloadLayers: Int = 0, // -1 means auto
    val numCtx: Int = 2048,
    val numBatch: Int = 512,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val enableMmap: Boolean = true,
    val enableMlock: Boolean = false,
    val keepAliveSeconds: Int = 60
)

/**
 * Mapper for converting between different Llama model representations.
 */
class LlamaModelMapper {
    /**
     * Parse parameter count string (e.g., "7B", "13B", "70B") to millions.
     */
    fun parseParameterCount(str: String?): Int? {
        return str?.let {
            val match = Regex("([\\d]+)B").find(it)
            match?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /**
     * Parse quantization code to numeric value for sorting.
     */
    fun parseQuantizationLevel(code: String?): Int {
        return when (code) {
            "Q2_K" -> 2
            "Q3_K_S" -> 3
            "Q3_K_M" -> 3
            "Q3_K_L" -> 3
            "Q4_0" -> 4
            "Q4_K_S" -> 4
            "Q4_K_M" -> 4
            "Q5_0" -> 5
            "Q5_K_S" -> 5
            "Q5_K_M" -> 5
            "Q6_K" -> 6
            "Q8_0" -> 8
            else -> 0
        }
    }

    /**
     * Sort models by quantization level (higher = better quality).
     */
    fun sortModelsByQuality(models: List<LlamaModelInfo>): List<LlamaModelInfo> {
        return models.sortedWith(compareBy({ -parseQuantizationLevel(it.quantizationCode) }, { it.parameterCountMillions ?: 0 }))
    }

    /**
     * Sort models by parameter count (higher = more capable).
     */
    fun sortModelsBySize(models: List<LlamaModelInfo>): List<LlamaModelInfo> {
        return models.sortedWith(compareBy({ it.parameterCountMillions ?: 0 }.reversed()))
    }

    /**
     * Sort models by server load (lower = faster).
     */
    fun sortModelsByLoad(models: List<LlamaModelInfo>): List<LlamaModelInfo> {
        return models.sortedWith(compareBy({ it.serverLoadPercent }))
    }
}