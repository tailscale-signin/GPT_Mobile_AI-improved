package dev.chungjungsoo.gptmobile.data.localruntime

/** Uniform FP16 K/V attention only. Recurrent and sliding-window layouts retain the fallback. */
internal fun uniformGgufKvBytesPerToken(metadata: Map<String, String>): Long? = runCatching {
    val architecture = metadata["general.architecture"] ?: return null
    if (architecture !in setOf("llama", "qwen2")) return null
    fun dimension(suffix: String): Long = requireNotNull(metadata["$architecture.$suffix"]?.toLongOrNull()).also { require(it in 1..131072) }
    val layers = dimension("block_count")
    val heads = dimension("attention.head_count")
    val kvHeads = dimension("attention.head_count_kv")
    val embedding = dimension("embedding_length")
    require(kvHeads <= heads && embedding % heads == 0L)
    val key = metadata["$architecture.attention.key_length"]?.toLongOrNull() ?: embedding / heads
    val value = metadata["$architecture.attention.value_length"]?.toLongOrNull() ?: embedding / heads
    require(key in 1..131072 && value in 1..131072)
    Math.multiplyExact(Math.multiplyExact(layers, kvHeads), Math.multiplyExact(key + value, 2L))
}.getOrNull()
