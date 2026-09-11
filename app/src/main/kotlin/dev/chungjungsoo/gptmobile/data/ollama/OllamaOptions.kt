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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val numGpu: Int? = DEFAULT_NUM_GPU,

    @SerialName("num_ctx")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val numCtx: Int? = DEFAULT_NUM_CTX,

    @SerialName("num_batch")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val numBatch: Int? = DEFAULT_NUM_BATCH,

    @SerialName("num_thread")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val numThread: Int? = DEFAULT_NUM_THREAD,

    @SerialName("temperature")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Float? = DEFAULT_TEMPERATURE,

    @SerialName("top_p")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val topP: Float? = DEFAULT_TOP_P,

    @SerialName("top_k")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val topK: Int? = DEFAULT_TOP_K,

    @SerialName("repeat_penalty")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val repeatPenalty: Float? = DEFAULT_REPEAT_PENALTY,

    @SerialName("seed")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val seed: Int? = DEFAULT_SEED,

    @SerialName("stop")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stop: List<String>? = DEFAULT_STOP
) {
    companion object {
        const val DEFAULT_NUM_GPU = 999
        const val DEFAULT_NUM_CTX = 8192
        const val DEFAULT_NUM_BATCH = 512
        const val DEFAULT_NUM_THREAD = 10
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_TOP_P = 0.15f
        const val DEFAULT_TOP_K = 30
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_SEED = 42
        val DEFAULT_STOP = listOf("```end", "delimiter", "You")

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
            stop = DEFAULT_STOP
        )
    }
}
