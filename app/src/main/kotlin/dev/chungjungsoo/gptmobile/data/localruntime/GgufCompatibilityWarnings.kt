package dev.chungjungsoo.gptmobile.data.localruntime

/** Only structural metadata checks; runtime EOG/template validation remains authoritative. */
internal fun ggufCompatibilityWarnings(metadata: Map<String, String>): List<String> = buildList {
    val vocabulary = metadata["tokenizer.ggml.vocab_size"]?.toLongOrNull()
    for (key in listOf("tokenizer.ggml.eos_token_id", "tokenizer.ggml.eot_token_id", "tokenizer.ggml.eom_token_id")) {
        val raw = metadata[key] ?: continue
        val id = raw.toLongOrNull()
        if (id == -1L || id == 0xffffffffL) {
            add("$key is explicitly unset; native end-of-generation detection must validate the template before execution.")
            continue
        }
        require(id != null && id >= 0 && (vocabulary == null || id < vocabulary)) { "GGUF_TOKENIZER_INCOMPATIBLE: $key is outside the declared vocabulary. Import a corrected chat-tuned export." }
    }
    val architecture = metadata["general.architecture"]
    val window = metadata["$architecture.attention.sliding_window"]?.toLongOrNull()
    if (window != null && window > 0) add("This export uses sliding-window attention. The runtime may allocate a full context cache; use Auto context and the recorded native memory receipt when sizing it.")
    if (metadata["tokenizer.ggml.eos_token_id"] == null && metadata["tokenizer.ggml.eot_token_id"] == null) {
        add("No EOS/EOT identifier is declared. End-of-generation compatibility remains unverified until the native tokenizer and template checks finish.")
    }
}
