package dev.chungjungsoo.gptmobile.data.ollama

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Advanced configuration options for Ollama inference.
 * See https://github.com/ollama/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OllamaOptions(
    @SerialName("num_gpu")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val numGpu: Int? = DEFAULT_NUM_GPU,

    @SerialName("num_ctx")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val numCtx: Int? = DEFAULT_NUM_CTX,

    @SerialName("num_batch")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val numBatch: Int? = DEFAULT_NUM_BATCH,

    @SerialName("num_thread")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val numThread: Int? = DEFAULT_NUM_THREAD,

    @SerialName("temperature")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val temperature: Float? = DEFAULT_TEMPERATURE,

    @SerialName("top_p")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val topP: Float? = DEFAULT_TOP_P,

    @SerialName("top_k")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val topK: Int? = DEFAULT_TOP_K,

    @SerialName("repeat_penalty")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val repeatPenalty: Float? = DEFAULT_REPEAT_PENALTY,

    @SerialName("seed")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val seed: Int? = DEFAULT_SEED,

    @SerialName("stop")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val stop: List<String>? = DEFAULT_STOP,

    @SerialName("flash_attention")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val flashAttention: Boolean? = DEFAULT_FLASH_ATTENTION,

    @SerialName("kv_cache_type")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val kvCacheType: String? = DEFAULT_KV_CACHE_TYPE,

    @SerialName("keep_alive")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val keepAlive: String? = DEFAULT_KEEP_ALIVE,

    @SerialName("num_parallel")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val numParallel: Int? = DEFAULT_NUM_PARALLEL,

    @SerialName("gpu_overhead")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val gpuOverhead: Long? = DEFAULT_GPU_OVERHEAD,

    @SerialName("auto_continue")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val autoContinue: Boolean? = DEFAULT_AUTO_CONTINUE,

    @SerialName("max_auto_continues")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val maxAutoContinues: Int? = DEFAULT_MAX_AUTO_CONTINUES,

    @SerialName("auto_continue_token_threshold")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val autoContinueTokenThreshold: Int? = DEFAULT_AUTO_CONTINUE_TOKEN_THRESHOLD
) {
    companion object {
        const val DEFAULT_NUM_GPU = 999
        const val DEFAULT_NUM_CTX = 4096
        const val DEFAULT_NUM_BATCH = 512
        const val DEFAULT_NUM_THREAD = 10
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_TOP_P = 0.15f
        const val DEFAULT_TOP_K = 30
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_SEED = 42
        val DEFAULT_STOP = listOf("```end", "delimiter", "You")
        const val DEFAULT_FLASH_ATTENTION = true
        const val DEFAULT_KV_CACHE_TYPE = "q8_0"
        const val DEFAULT_KEEP_ALIVE = "30m"
        const val DEFAULT_NUM_PARALLEL = 2
        const val DEFAULT_GPU_OVERHEAD = 1073741824L
        const val DEFAULT_AUTO_CONTINUE = false
        const val DEFAULT_MAX_AUTO_CONTINUES = 3
        const val DEFAULT_AUTO_CONTINUE_TOKEN_THRESHOLD = 100

        fun createDefault(): OllamaOptions = OllamaOptions(
            numGpu = DEFAULT_NUM_GPU,
            numCtx = DEFAULT_NUM_CTX,
            numBatch = DEFAULT_NUM_BATCH,
            numThread = DEFAULT_NUM_THREAD,
            temperature = DEFAULT_TEMPERATURE,
            topP = DEFAULT_TOP_P,
            topK = DEFAULT_TOP_K,
            repeatPenalty = DEFAULT_REPEAT_PENALTY,
            seed = DEFAULT_SEED,
            stop = DEFAULT_STOP,
            flashAttention = DEFAULT_FLASH_ATTENTION,
            kvCacheType = DEFAULT_KV_CACHE_TYPE,
            keepAlive = DEFAULT_KEEP_ALIVE,
            numParallel = DEFAULT_NUM_PARALLEL,
            gpuOverhead = DEFAULT_GPU_OVERHEAD,
            autoContinue = DEFAULT_AUTO_CONTINUE,
            maxAutoContinues = DEFAULT_MAX_AUTO_CONTINUES,
            autoContinueTokenThreshold = DEFAULT_AUTO_CONTINUE_TOKEN_THRESHOLD
        )
    }
}
